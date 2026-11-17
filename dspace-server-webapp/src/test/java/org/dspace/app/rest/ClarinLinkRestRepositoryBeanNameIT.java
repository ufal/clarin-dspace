/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.dspace.app.rest.exception.RepositoryNotFoundException;
import org.dspace.app.rest.model.BitstreamRest;
import org.dspace.app.rest.model.ClarinLicenseResourceMappingRest;
import org.dspace.app.rest.model.ClarinLicenseResourceUserAllowanceRest;
import org.dspace.app.rest.model.ClarinUserRegistrationRest;
import org.dspace.app.rest.model.LinkRest;
import org.dspace.app.rest.model.LinksRest;
import org.dspace.app.rest.model.RestAddressableModel;
import org.dspace.app.rest.repository.LinkRestRepository;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.rest.utils.Utils;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.ClarinLicenseBuilder;
import org.dspace.builder.ClarinLicenseLabelBuilder;
import org.dspace.builder.ClarinLicenseResourceMappingBuilder;
import org.dspace.builder.ClarinLicenseResourceUserAllowanceBuilder;
import org.dspace.builder.ClarinUserMetadataBuilder;
import org.dspace.builder.ClarinUserRegistrationBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.clarin.ClarinLicenseLabel;
import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.clarin.ClarinLicenseResourceUserAllowance;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.ClarinLicenseLabelService;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.content.service.clarin.ClarinLicenseService;
import org.dspace.eperson.EPerson;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Guards the DSpace 9 link-repository contract for the CLARIN models: the bean naming that decides
 * whether a rel resolves at all, and the HTTP status the rel answers for each role.
 * <P>
 * DSpace 7 resolved a rel by singularizing the URL segment before the bean lookup
 * ({@code Utils.getLinkResourceRepository} called {@code makeSingular}). DSpace 9 removed that
 * step and looks the bean up under the plural segment verbatim, so every
 * {@link LinkRestRepository} must be registered as
 * {@code category.typePlural.rel}. A repository still registered under the singular name
 * is simply never found: the lookup raises {@code RepositoryNotFoundException}, the client gets
 * a 404, and because a missing route resolves BEFORE any authorization check the same defect
 * shows up as "404 instead of 200" for an admin and "404 instead of 401/403" for everyone else.
 * It therefore reads like an authorization bug while the object's own {@code _links} keep
 * advertising the dead rels.
 * <P>
 * Registering the beans under the plural name then exposed a second defect that the dead route had
 * been hiding: the repository body ran for the first time and answered anonymous callers with
 * HTTP 500 instead of 401. Two causes, both in fork code -- a {@code NullPointerException} in
 * {@code ClarinUserRegistrationServiceImpl.authorizeClarinUserRegistrationAction} for a context
 * with no current user, and a checked {@code AuthorizeException} escaping
 * {@code CLRUAResourceMappingLinkRepository}, which {@code RestResourceController.findRelInternal}
 * wraps into a {@code RuntimeException}. The role tests below lock both in.
 * <P>
 * The bean-name tests assert the invariant directly instead of going through HTTP, because the link
 * repositories also raise {@code ResourceNotFoundException} (another 404) when the linked data
 * simply does not exist -- an endpoint test could not tell the two apart without fixtures for
 * every entity type. They are driven off the {@link LinksRest} annotation and off the registered
 * beans rather than a hardcoded list, so a rel added to any of these models is covered
 * automatically.
 */
public class ClarinLinkRestRepositoryBeanNameIT extends AbstractControllerIntegrationTest {

    private static final String USER_REGISTRATIONS_URL = "/api/core/clarinuserregistrations/";
    private static final String ALLOWANCES_URL = "/api/core/clarinlruallowances/";
    private static final String OTHER_EPERSON_EMAIL = "other-eperson@mail.com";

    /** An id no fixture can own, used to ask about an entity that does not exist. */
    private static final int UNKNOWN_ID = Integer.MAX_VALUE;

    /**
     * The CLARIN models that own rels. The cells are read off their {@link LinksRest} annotations rather
     * than hardcoded, so a rel added to any of them is covered without touching this test - the same
     * reason {@code _sync3/sweeps/rest-matrix.sh} enumerates from the source instead of from a list.
     */
    private static final List<Class<? extends RestAddressableModel>> CLARIN_MODELS_WITH_RELS = List.of(
            ClarinLicenseResourceUserAllowanceRest.class,
            ClarinUserRegistrationRest.class,
            ClarinLicenseResourceMappingRest.class);

    @Autowired
    private Utils utils;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ClarinLicenseService clarinLicenseService;

    @Autowired
    private ClarinLicenseLabelService clarinLicenseLabelService;

    @Autowired
    private ClarinLicenseResourceMappingService clarinLicenseResourceMappingService;

    private ClarinLicenseResourceUserAllowance allowance;

    private ClarinLicenseResourceMapping publicResourceMapping;

    /**
     * The CLARIN builders are not part of the ordered cleanup map, so they are torn down in the order they
     * were first used: the user registration would be deleted while the allowance still references it. Drop
     * the allowance first, the way {@code ClarinLicenseResourceUserAllowanceServiceImplIT} does.
     */
    @Override
    public void destroy() throws Exception {
        if (allowance != null) {
            ClarinLicenseResourceUserAllowanceBuilder.deleteClarinLicenseResourceUserAllowance(allowance.getID());
            allowance = null;
        }
        // Same reason: this mapping carries a licence, whose builder would otherwise be torn down first.
        if (publicResourceMapping != null) {
            ClarinLicenseResourceMappingBuilder.delete(publicResourceMapping.getID());
            publicResourceMapping = null;
        }
        super.destroy();
    }

    /**
     * Asserts that every rel declared via {@link LinksRest} on the given model resolves to a
     * registered {@link LinkRestRepository}, using the same lookup {@link RestResourceController}
     * performs when a client traverses the rel.
     *
     * @param modelClass the REST model whose declared rels should all be resolvable
     */
    private void assertAllDeclaredRelsResolve(Class<? extends RestAddressableModel> modelClass)
            throws ReflectiveOperationException {
        RestAddressableModel model = modelClass.getDeclaredConstructor().newInstance();
        LinksRest linksRest = modelClass.getDeclaredAnnotation(LinksRest.class);
        assertNotNull(modelClass.getSimpleName() + " is expected to declare @LinksRest", linksRest);
        assertTrue(modelClass.getSimpleName() + " is expected to declare at least one @LinkRest",
                linksRest.links().length > 0);

        for (LinkRest linkRest : linksRest.links()) {
            assertRelResolves(model.getCategory(), model.getTypePlural(), linkRest.name());
        }
    }

    /**
     * Asserts that a single rel resolves to a registered {@link LinkRestRepository}.
     *
     * @param category   the REST category of the model owning the rel
     * @param typePlural the plural model name, as it appears in the URL
     * @param rel        the name of the rel
     */
    private void assertRelResolves(String category, String typePlural, String rel) {
        String expectedBeanName = category + "." + typePlural + "." + rel;
        try {
            LinkRestRepository repository = utils.getLinkResourceRepository(category, typePlural, rel);
            assertNotNull("No LinkRestRepository registered as '" + expectedBeanName + "'", repository);
        } catch (RepositoryNotFoundException e) {
            // Translate the lookup failure into an actionable assertion. RepositoryNotFoundException
            // reports only "<category>.<typePlural>" and never the rel, so on a model with several
            // rels its own message cannot say which one is unregistered. It is also misleading here:
            // it claims the repository *type* is missing when the main repository resolves fine and
            // only the link repository bean is absent.
            throw new AssertionError("No LinkRestRepository is registered as '" + expectedBeanName
                    + "'. On DSpace 9 link repositories are looked up under the plural model name, so"
                    + " the @Component of the repository serving this rel must be built from"
                    + " PLURAL_NAME, not NAME.", e);
        }
    }

    @Test
    public void clarinLicenseResourceUserAllowanceRelsResolve() throws Exception {
        // resourceMapping, userRegistration, userMetadata
        assertAllDeclaredRelsResolve(ClarinLicenseResourceUserAllowanceRest.class);
    }

    @Test
    public void clarinUserRegistrationRelsResolve() throws Exception {
        // clarinLicenses, userMetadata
        assertAllDeclaredRelsResolve(ClarinUserRegistrationRest.class);
    }

    @Test
    public void clarinLicenseResourceMappingRelsResolve() throws Exception {
        // clarinLicense
        assertAllDeclaredRelsResolve(ClarinLicenseResourceMappingRest.class);
    }

    /**
     * The fork adds one rel to a vanilla model, so the CLARIN-model tests above do not cover it. Its bean
     * was made plural by a different commit than the six CLARIN ones, which is why it needs its own guard.
     */
    @Test
    public void bitstreamChecksumRelResolves() {
        assertRelResolves(BitstreamRest.CATEGORY, BitstreamRest.PLURAL_NAME, BitstreamRest.CHECKSUM);
    }

    /**
     * The model-driven tests above only cover the models somebody remembered to list. This one covers
     * every registered link repository, including one belonging to a model added later: a link repository
     * bean is named {@code category.typePlural.rel}, so its {@code category.typePlural} prefix must itself
     * be a registered bean -- the main repository, which is registered under the plural name. A link
     * repository still built from the singular {@code NAME} names a prefix that no bean answers to, and
     * fails here with its own bean name in the message.
     */
    @Test
    public void everyRegisteredLinkRepositoryUsesThePluralModelName() {
        Map<String, LinkRestRepository> linkRepositories =
                applicationContext.getBeansOfType(LinkRestRepository.class);
        assertTrue("No LinkRestRepository beans found - the lookup itself is broken",
                linkRepositories.size() > 0);

        for (String beanName : linkRepositories.keySet()) {
            String[] segments = beanName.split("\\.");
            assertEquals("The link repository bean '" + beanName + "' is not named category.typePlural.rel",
                    3, segments.length);
            String modelBeanName = segments[0] + "." + segments[1];
            assertTrue("The link repository bean '" + beanName + "' refers to the model '" + modelBeanName
                            + "', for which no repository is registered. On DSpace 9 the model segment must be"
                            + " the PLURAL_NAME of the model, not its NAME.",
                    applicationContext.containsBean(modelBeanName));
        }
    }

    /**
     * Builds a CLARIN user registration owned by {@code eperson}, with one user metadata row attached.
     *
     * @return the user registration
     */
    private ClarinUserRegistration userRegistrationOwnedByEPerson() throws Exception {
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .withEPersonID(eperson.getID())
                .build();
        ClarinUserMetadataBuilder.createClarinUserMetadata(context)
                .withUserRegistration(clarinUserRegistration)
                .build();
        context.restoreAuthSystemState();
        return clarinUserRegistration;
    }

    /**
     * Builds a second EPerson, so that a caller who is authenticated but owns nothing can be tested.
     *
     * @return the other EPerson
     */
    private EPerson otherEPerson() throws Exception {
        context.turnOffAuthorisationSystem();
        EPerson otherEPerson = EPersonBuilder.createEPerson(context)
                .withEmail(OTHER_EPERSON_EMAIL)
                .withPassword(password)
                .build();
        context.restoreAuthSystemState();
        return otherEPerson;
    }

    /**
     * Both rels of a user registration answered 500 anonymously, because the authorization helper
     * dereferenced the (null) current user before it could throw AuthorizeException. The parent resource
     * answers 401, so the rels must answer 401 too.
     */
    @Test
    public void anonymousUserRegistrationRelsAreUnauthorized() throws Exception {
        ClarinUserRegistration clarinUserRegistration = userRegistrationOwnedByEPerson();

        getClient().perform(get(USER_REGISTRATIONS_URL + clarinUserRegistration.getID()))
                .andExpect(status().isUnauthorized());
        getClient().perform(get(USER_REGISTRATIONS_URL + clarinUserRegistration.getID() + "/userMetadata"))
                .andExpect(status().isUnauthorized());
        getClient().perform(get(USER_REGISTRATIONS_URL + clarinUserRegistration.getID() + "/clarinLicenses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void adminUserRegistrationRelsAreOk() throws Exception {
        ClarinUserRegistration clarinUserRegistration = userRegistrationOwnedByEPerson();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get(USER_REGISTRATIONS_URL + clarinUserRegistration.getID() + "/userMetadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.userMetadata", hasSize(1)));
        getClient(adminToken).perform(get(USER_REGISTRATIONS_URL + clarinUserRegistration.getID() + "/clarinLicenses"))
                .andExpect(status().isOk());
    }

    /**
     * A logged-in user who does not own the registration must be refused, not served and not answered with
     * a 500; the owner must still be served, which is what proves the fix does not simply deny everybody.
     */
    @Test
    public void otherUserRegistrationRelsAreForbidden() throws Exception {
        ClarinUserRegistration clarinUserRegistration = userRegistrationOwnedByEPerson();
        String otherUserToken = getAuthToken(otherEPerson().getEmail(), password);

        getClient(otherUserToken)
                .perform(get(USER_REGISTRATIONS_URL + clarinUserRegistration.getID() + "/userMetadata"))
                .andExpect(status().isForbidden());
        getClient(otherUserToken)
                .perform(get(USER_REGISTRATIONS_URL + clarinUserRegistration.getID() + "/clarinLicenses"))
                .andExpect(status().isForbidden());

        String ownerToken = getAuthToken(eperson.getEmail(), password);
        getClient(ownerToken)
                .perform(get(USER_REGISTRATIONS_URL + clarinUserRegistration.getID() + "/userMetadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.userMetadata", hasSize(1)));
        getClient(ownerToken)
                .perform(get(USER_REGISTRATIONS_URL + clarinUserRegistration.getID() + "/clarinLicenses"))
                .andExpect(status().isOk());
    }

    /**
     * Builds a licence resource user allowance owned by {@code eperson}. The resource mapping is attached
     * because the repository answers 404 when it is missing, which would hide the status under test.
     *
     * @return the licence resource user allowance
     */
    private ClarinLicenseResourceUserAllowance allowanceOwnedByEPerson() throws Exception {
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .withEPersonID(eperson.getID())
                .build();
        allowance = ClarinLicenseResourceUserAllowanceBuilder
                .createClarinLicenseResourceUserAllowance(context)
                .withUser(clarinUserRegistration)
                .withMapping(ClarinLicenseResourceMappingBuilder.createClarinLicenseResourceMapping(context).build())
                .build();
        context.restoreAuthSystemState();
        return allowance;
    }

    /**
     * The resourceMapping rel answered 500 for every caller, because its repository was the only one of the
     * three siblings that let the checked AuthorizeException escape. The two denial cases are the
     * regression. The serving case for this fixture is covered by
     * {@link #clruaUserRegistrationAndUserMetadataRelsHonourRoles()}, which reaches the same service
     * authorization through a rel whose converter does not need a bitstream.
     */
    @Test
    public void anonymousAndForeignClruaResourceMappingIsDenied() throws Exception {
        ClarinLicenseResourceUserAllowance allowance = allowanceOwnedByEPerson();

        getClient().perform(get(ALLOWANCES_URL + allowance.getID() + "/resourceMapping"))
                .andExpect(status().isUnauthorized());

        String otherUserToken = getAuthToken(otherEPerson().getEmail(), password);
        getClient(otherUserToken).perform(get(ALLOWANCES_URL + allowance.getID() + "/resourceMapping"))
                .andExpect(status().isForbidden());
    }

    /**
     * These two rels already answered 401/403 before the fix, through a different exception type. Locking
     * their statuses in keeps a later alignment of the idiom from silently regressing them.
     */
    @Test
    public void clruaUserRegistrationAndUserMetadataRelsHonourRoles() throws Exception {
        ClarinLicenseResourceUserAllowance allowance = allowanceOwnedByEPerson();
        String otherUserToken = getAuthToken(otherEPerson().getEmail(), password);

        for (String rel : new String[] {"userRegistration", "userMetadata"}) {
            getClient().perform(get(ALLOWANCES_URL + allowance.getID() + "/" + rel))
                    .andExpect(status().isUnauthorized());
            getClient(otherUserToken).perform(get(ALLOWANCES_URL + allowance.getID() + "/" + rel))
                    .andExpect(status().isForbidden());
        }

        // The userMetadata rel answers 404 when the allowance carries none, so only the rel that always has
        // a value can assert that the owner and the administrator are served.
        String ownerToken = getAuthToken(eperson.getEmail(), password);
        getClient(ownerToken).perform(get(ALLOWANCES_URL + allowance.getID() + "/userRegistration"))
                .andExpect(status().isOk());
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get(ALLOWANCES_URL + allowance.getID() + "/userRegistration"))
                .andExpect(status().isOk());
    }
    /**
     * Enumerates every CLARIN rel cell from the {@link LinksRest} annotations of the models above, the way
     * {@code _sync3/sweeps/rest-matrix.sh} does from the source.
     *
     * @return one {category, typePlural, rel} triple per declared rel
     */
    private List<String[]> clarinRelCells() throws ReflectiveOperationException {
        List<String[]> cells = new ArrayList<>();
        for (Class<? extends RestAddressableModel> modelClass : CLARIN_MODELS_WITH_RELS) {
            RestAddressableModel model = modelClass.getDeclaredConstructor().newInstance();
            LinksRest linksRest = modelClass.getDeclaredAnnotation(LinksRest.class);
            assertNotNull(modelClass.getSimpleName() + " is expected to declare @LinksRest", linksRest);
            for (LinkRest linkRest : linksRest.links()) {
                cells.add(new String[] {model.getCategory(), model.getTypePlural(), linkRest.name()});
            }
        }
        return cells;
    }

    private String parentUrl(String[] cell, Object id) {
        return "/api/" + cell[0] + "/" + cell[1] + "/" + id;
    }

    private int anonymousStatus(String url) throws Exception {
        return getClient().perform(get(url)).andReturn().getResponse().getStatus();
    }

    /**
     * A rel must answer an anonymous caller exactly as its own parent does, or the status code becomes an
     * oracle: {@code ClarinLicenseResourceUserAllowanceService.find} returns null for a missing row before
     * {@code authorizeClruaAction} ever runs, so an unguarded link method answered 404 for an unknown id and
     * 401 for an existing one, while the parent findOne answers 401 for both. Anonymous callers could
     * therefore probe which allowance ids exist.
     * <P>
     * The cells come from the models' own annotations, so this covers all six CLARIN rels rather than the two
     * that leaked, and picks up any rel added later.
     */
    @Test
    public void anonymousRelStatusMatchesParentForUnknownId() throws Exception {
        List<String[]> cells = clarinRelCells();
        assertEquals("Expected the six CLARIN rel cells; the matrix changed shape: " + cells.size(),
                6, cells.size());

        for (String[] cell : cells) {
            String parent = parentUrl(cell, UNKNOWN_ID);
            String rel = parent + "/" + cell[2];
            assertEquals("Anonymous " + rel + " must answer the same status as its parent " + parent,
                    anonymousStatus(parent), anonymousStatus(rel));
        }
    }

    /**
     * The other half of the same leak, seen from the entity rather than from the parent: for an anonymous
     * caller the answer must not depend on whether the allowance exists.
     */
    @Test
    public void anonymousClruaRelsRevealNothingAboutExistence() throws Exception {
        ClarinLicenseResourceUserAllowance existing = allowanceOwnedByEPerson();

        for (String rel : new String[] {ClarinLicenseResourceUserAllowanceRest.RESOURCE_MAPPING,
                                        ClarinLicenseResourceUserAllowanceRest.USER_REGISTRATION,
                                        ClarinLicenseResourceUserAllowanceRest.USER_METADATA}) {
            int onExisting = anonymousStatus(ALLOWANCES_URL + existing.getID() + "/" + rel);
            int onUnknown = anonymousStatus(ALLOWANCES_URL + UNKNOWN_ID + "/" + rel);
            assertEquals("The anonymous status of the " + rel + " rel tells the caller whether the allowance"
                            + " exists (existing id vs unknown id)", onExisting, onUnknown);
        }
    }

    /**
     * The not-found message of the userMetadata rel said "for if:" instead of "for id:" on both dtq-dev and
     * the v9 base. An authenticated caller is past the guard, so this is the caller who can still see it.
     */
    @Test
    public void clruaUserMetadataNotFoundMessageUsesId() throws Exception {
        String epersonToken = getAuthToken(eperson.getEmail(), password);
        MockHttpServletResponse response = getClient(epersonToken)
                .perform(get(ALLOWANCES_URL + UNKNOWN_ID + "/"
                        + ClarinLicenseResourceUserAllowanceRest.USER_METADATA))
                .andExpect(status().isNotFound())
                .andReturn().getResponse();
        // MockMvc renders no error page, so the text of a sendError() lands in the error message rather
        // than in the body; read both so the assertion holds however the advice reports it.
        String message = Objects.toString(response.getErrorMessage(), "") + response.getContentAsString();

        assertTrue("The not-found message should read \"for id: \", but was: " + message,
                message.contains("for id: "));
        assertFalse("The \"for if: \" typo is back: " + message, message.contains("for if: "));
    }

    /**
     * Guard against over-fixing. {@code ClarinResourceMappingCLicenseLinkRepository} must stay unguarded:
     * its parent {@code ClarinLicenseResourceMappingRestRepository.findOne} is {@code permitAll()} and the
     * Angular licence agreement page follows this rel anonymously, so adding {@code @PreAuthorize} here in a
     * later "security cleanup" would break the anonymous download flow.
     */
    @Test
    public void anonymousResourceMappingClarinLicenseRelStaysPublic() throws Exception {
        ClarinLicenseResourceMapping mapping = resourceMappingWithLicence();

        getClient().perform(get("/api/" + ClarinLicenseResourceMappingRest.CATEGORY + "/"
                        + ClarinLicenseResourceMappingRest.PLURAL_NAME + "/" + mapping.getID() + "/"
                        + ClarinLicenseResourceMappingRest.CLARIN_LICENSE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(mapping.getLicense().getID())));
    }

    /**
     * Builds a bitstream with a CLARIN licence attached, i.e. the resource mapping the licence agreement page
     * reads anonymously.
     *
     * @return the resource mapping created by attaching the licence
     */
    private ClarinLicenseResourceMapping resourceMappingWithLicence() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1")
                .build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Item with a licensed bitstream")
                .withIssueDate("2026-09-10")
                .build();
        Bitstream bitstream;
        try (InputStream is = new ByteArrayInputStream("public".getBytes(StandardCharsets.UTF_8))) {
            bitstream = BitstreamBuilder.createBitstream(context, item, is)
                    .withName("public.txt")
                    .withMimeType("text/plain")
                    .build();
        }

        ClarinLicenseLabel label = ClarinLicenseLabelBuilder.createClarinLicenseLabel(context).build();
        label.setLabel("PUB");
        label.setTitle("Public rel label");
        label.setExtended(false);
        clarinLicenseLabelService.update(context, label);

        ClarinLicense licence = ClarinLicenseBuilder.createClarinLicense(context).build();
        licence.setName("Public rel licence");
        licence.setDefinition("http://example.com/licence");
        licence.setRequiredInfo("NAME");
        licence.setConfirmation(ClarinLicense.Confirmation.NOT_REQUIRED);
        HashSet<ClarinLicenseLabel> labels = new HashSet<>();
        labels.add(label);
        licence.setLicenseLabels(labels);
        clarinLicenseService.update(context, licence);

        clarinLicenseResourceMappingService.attachLicense(context, licence, bitstream);
        List<ClarinLicenseResourceMapping> mappings =
                clarinLicenseResourceMappingService.findByBitstreamUUID(context, bitstream.getID());
        assertEquals("The licence fixture did not attach exactly one resource mapping", 1, mappings.size());
        publicResourceMapping = mappings.get(0);
        context.restoreAuthSystemState();
        return publicResourceMapping;
    }
}
