/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.clarin;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.service.ResourcePolicyService;
import org.dspace.content.Item;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.clarin.ClarinTokenService;
import org.dspace.content.service.clarin.ClarinUserRegistrationService;
import org.dspace.content.service.clarin.MatomoReportSubscriptionService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.Subscription;
import org.dspace.eperson.dao.GroupDAO;
import org.dspace.eperson.dao.SubscriptionDAO;
import org.dspace.eperson.dao.clarin.EPersonNetidAliasDAO;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.dspace.eperson.service.SubscribeService;
import org.dspace.eperson.service.clarin.ClarinIdentityService;
import org.dspace.eperson.service.clarin.EPersonMergeAuditService;
import org.dspace.eperson.service.clarin.EPersonMergeService;
import org.dspace.orcid.OrcidToken;
import org.dspace.orcid.service.OrcidTokenService;
import org.dspace.scripts.Process;
import org.dspace.scripts.service.ProcessService;
import org.dspace.versioning.Version;
import org.dspace.versioning.service.VersioningService;
import org.dspace.xmlworkflow.storedcomponents.ClaimedTask;
import org.dspace.xmlworkflow.storedcomponents.InProgressUser;
import org.dspace.xmlworkflow.storedcomponents.PoolTask;
import org.dspace.xmlworkflow.storedcomponents.WorkflowItemRole;
import org.dspace.xmlworkflow.storedcomponents.service.ClaimedTaskService;
import org.dspace.xmlworkflow.storedcomponents.service.InProgressUserService;
import org.dspace.xmlworkflow.storedcomponents.service.PoolTaskService;
import org.dspace.xmlworkflow.storedcomponents.service.WorkflowItemRoleService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @see EPersonMergeService
 *
 * @author Ondrej Kosarko
 */
public class EPersonMergeServiceImpl implements EPersonMergeService {

    private static final Logger log = LogManager.getLogger(EPersonMergeServiceImpl.class);

    public static final String TOOL_VERSION = "1";

    /**
     * Matches the {@code length} constraint on the {@code eperson.email} column
     * ({@link org.dspace.eperson.EPerson#getEmail()}).
     */
    private static final int EMAIL_MAX_LENGTH = 64;

    @Autowired
    private EPersonService ePersonService;
    @Autowired
    private ItemService itemService;
    @Autowired
    private ResourcePolicyService resourcePolicyService;
    @Autowired
    private GroupDAO groupDAO;
    @Autowired
    private GroupService groupService;
    @Autowired
    private WorkflowItemRoleService workflowItemRoleService;
    @Autowired
    private PoolTaskService poolTaskService;
    @Autowired
    private ClaimedTaskService claimedTaskService;
    @Autowired
    private InProgressUserService inProgressUserService;
    @Autowired
    private SubscribeService subscribeService;
    @Autowired
    private SubscriptionDAO subscriptionDAO;
    @Autowired
    private ProcessService processService;
    @Autowired
    private VersioningService versioningService;
    @Autowired
    private OrcidTokenService orcidTokenService;
    @Autowired
    private ClarinTokenService clarinTokenService;
    @Autowired
    private MatomoReportSubscriptionService matomoReportSubscriptionService;
    @Autowired
    private ClarinUserRegistrationService clarinUserRegistrationService;
    @Autowired
    private ClarinIdentityService identityService;
    @Autowired
    private EPersonNetidAliasDAO ePersonNetidAliasDAO;
    @Autowired
    private EPersonMergeAuditService ePersonMergeAuditService;

    @Override
    public EPersonMergeAudit merge(Context context, EPerson from, EPerson to, EPerson performedBy)
            throws SQLException, AuthorizeException {
        if (Objects.equals(from.getID(), to.getID())) {
            throw new IllegalArgumentException("Cannot merge an EPerson into itself: " + from.getID());
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("toolVersion", TOOL_VERSION);
        detail.put("fromEmail", from.getEmail());
        detail.put("toEmail", to.getEmail());

        detail.put("item.submitter", reassignItemSubmitter(context, from, to));
        detail.put("resourcepolicy", reassignResourcePolicies(context, from, to));
        detail.put("group2eperson", reassignGroupMembership(context, from, to));
        detail.put("cwf_workflowitemrole", reassignWorkflowItemRoles(context, from, to));
        detail.put("cwf_pooltask", reassignPoolTasks(context, from, to));
        detail.put("cwf_claimtask", reassignClaimedTasks(context, from, to));
        detail.put("cwf_in_progress_user", reassignInProgressUsers(context, from, to));
        detail.put("subscription", reassignSubscriptions(context, from, to));
        detail.put("process", reassignProcesses(context, from, to));
        detail.put("versionitem", reassignVersions(context, from, to));
        detail.put("orcid_token", reassignOrcidToken(context, from, to));
        detail.put("clarin_token_deleted", deleteClarinTokens(context, from));
        detail.put("matomo_report_subscription", reassignMatomoSubscriptions(context, from, to));
        detail.put("user_registration", reassignUserRegistrations(context, from, to));
        detail.put("alias", reassignAliases(context, from, to));
        // supervision_order references only an epersongroup, not an eperson directly - any
        // membership `from` held in a supervision group was already moved by the group2eperson step.

        String parkedEmail = tombstone(context, from, to);
        detail.put("fromEmailParkedAs", parkedEmail);

        EPersonMergeAudit audit = ePersonMergeAuditService.record(context, from.getID(), to.getID(),
                performedBy == null ? null : performedBy.getID(), detail);

        log.info("Merged EPerson {} into {} (performed by {}): {}", from.getID(), to.getID(),
                performedBy == null ? "system" : performedBy.getID(), detail);

        return audit;
    }

    private int reassignItemSubmitter(Context context, EPerson from, EPerson to) throws SQLException {
        int count = 0;
        Iterator<Item> items = itemService.findBySubmitter(context, from);
        while (items.hasNext()) {
            Item item = items.next();
            item.setSubmitter(to);
            try {
                itemService.update(context, item);
            } catch (AuthorizeException e) {
                throw new IllegalStateException(e);
            }
            count++;
        }
        return count;
    }

    /**
     * Re-points resourcepolicy rows, skipping (leaving attached to the tombstoned `from`) any
     * that would duplicate a policy `to` already holds for the same resource/action/type.
     */
    private int reassignResourcePolicies(Context context, EPerson from, EPerson to) throws SQLException {
        List<ResourcePolicy> toPolicies = resourcePolicyService.findByEPerson(context, to, -1, -1);
        Set<String> toSignatures = new HashSet<>();
        for (ResourcePolicy rp : toPolicies) {
            toSignatures.add(resourcePolicySignature(rp));
        }

        int count = 0;
        for (ResourcePolicy rp : resourcePolicyService.findByEPerson(context, from, -1, -1)) {
            if (toSignatures.contains(resourcePolicySignature(rp))) {
                continue;
            }
            rp.setEPerson(to);
            try {
                resourcePolicyService.update(context, rp);
            } catch (AuthorizeException e) {
                throw new IllegalStateException(e);
            }
            count++;
        }
        return count;
    }

    private static String resourcePolicySignature(ResourcePolicy rp) {
        return rp.getdSpaceObject().getID() + "|" + rp.getAction() + "|" + rp.getRpType() + "|"
                + rp.getStartDate() + "|" + rp.getEndDate() + "|" + rp.getRpName() + "|" + rp.getRpDescription();
    }

    private int reassignGroupMembership(Context context, EPerson from, EPerson to) throws SQLException {
        int count = 0;
        for (Group group : groupDAO.findByEPerson(context, from)) {
            if (!groupService.isDirectMember(group, to)) {
                groupService.addMember(context, group, to);
            }
            groupService.removeMember(context, group, from);
            count++;
        }
        return count;
    }

    private int reassignWorkflowItemRoles(Context context, EPerson from, EPerson to)
            throws SQLException, AuthorizeException {
        int count = 0;
        for (WorkflowItemRole role : workflowItemRoleService.findByEPerson(context, from)) {
            role.setEPerson(to);
            workflowItemRoleService.update(context, role);
            count++;
        }
        return count;
    }

    private int reassignPoolTasks(Context context, EPerson from, EPerson to) throws SQLException, AuthorizeException {
        int count = 0;
        for (PoolTask task : poolTaskService.findByEPerson(context, from)) {
            task.setEperson(to);
            poolTaskService.update(context, task);
            count++;
        }
        return count;
    }

    private int reassignClaimedTasks(Context context, EPerson from, EPerson to)
            throws SQLException, AuthorizeException {
        int count = 0;
        for (ClaimedTask task : claimedTaskService.findByEperson(context, from)) {
            task.setOwner(to);
            claimedTaskService.update(context, task);
            count++;
        }
        return count;
    }

    /**
     * Skips (leaves with the tombstoned `from`) any in-progress-user row that would duplicate
     * one `to` already has for the same workflow item (unique constraint on workflowitem+user).
     */
    private int reassignInProgressUsers(Context context, EPerson from, EPerson to)
            throws SQLException, AuthorizeException {
        Set<Integer> toWorkflowItems = new HashSet<>();
        for (InProgressUser inProgressUser : inProgressUserService.findByEperson(context, to)) {
            toWorkflowItems.add(inProgressUser.getWorkflowItem().getID());
        }

        int count = 0;
        for (InProgressUser inProgressUser : inProgressUserService.findByEperson(context, from)) {
            if (toWorkflowItems.contains(inProgressUser.getWorkflowItem().getID())) {
                continue;
            }
            inProgressUser.setUser(to);
            inProgressUserService.update(context, inProgressUser);
            count++;
        }
        return count;
    }

    private int reassignSubscriptions(Context context, EPerson from, EPerson to) throws SQLException {
        int count = 0;
        for (Subscription subscription : subscribeService.findSubscriptionsByEPerson(context, from, -1, -1)) {
            if (subscribeService.isSubscribed(context, to, subscription.getDSpaceObject())) {
                subscribeService.deleteSubscription(context, subscription);
                continue;
            }
            subscription.setEPerson(to);
            subscriptionDAO.save(context, subscription);
            count++;
        }
        return count;
    }

    private int reassignProcesses(Context context, EPerson from, EPerson to) throws SQLException {
        int count = 0;
        for (Process process : processService.findByUser(context, from, -1, -1)) {
            process.setEPerson(to);
            processService.update(context, process);
            count++;
        }
        return count;
    }

    private int reassignVersions(Context context, EPerson from, EPerson to) throws SQLException {
        int count = 0;
        for (Version version : versioningService.findByEPerson(context, from)) {
            version.setePerson(to);
            versioningService.update(context, version);
            count++;
        }
        return count;
    }

    /**
     * orcid_token has a unique constraint on eperson_id: keep `to`'s token if it already has
     * one, otherwise move `from`'s token across.
     */
    private int reassignOrcidToken(Context context, EPerson from, EPerson to) {
        OrcidToken fromToken = orcidTokenService.findByEPerson(context, from);
        if (fromToken == null) {
            return 0;
        }
        if (orcidTokenService.findByEPerson(context, to) == null) {
            orcidTokenService.create(context, to, fromToken.getProfileItem(), fromToken.getAccessToken());
        }
        orcidTokenService.delete(context, fromToken);
        return 1;
    }

    /**
     * clarin_token is a short-lived JWT-style auth/linking token, not identity data - dropping
     * it is simpler and safer than trying to re-point it, since it will simply be re-issued the
     * next time the merged (`to`) account needs one.
     */
    private boolean deleteClarinTokens(Context context, EPerson from) throws SQLException {
        try {
            clarinTokenService.delete(context, from);
        } catch (AuthorizeException e) {
            throw new IllegalStateException(e);
        }
        return true;
    }

    private int reassignMatomoSubscriptions(Context context, EPerson from, EPerson to) throws SQLException {
        return matomoReportSubscriptionService.reassignEPerson(context, from, to);
    }

    /**
     * user_registration keeps all rows (it carries per-organization license-agreement history) -
     * every row simply follows the merged account.
     */
    private int reassignUserRegistrations(Context context, EPerson from, EPerson to) throws SQLException {
        int count = 0;
        try {
            for (ClarinUserRegistration registration :
                    clarinUserRegistrationService.findByEPersonUUID(context, from.getID())) {
                registration.setPersonID(to.getID());
                clarinUserRegistrationService.update(context, registration);
                count++;
            }
        } catch (AuthorizeException e) {
            throw new IllegalStateException(e);
        }
        return count;
    }

    private int reassignAliases(Context context, EPerson from, EPerson to) throws SQLException {
        int count = 0;
        for (EPersonNetidAlias alias : identityService.findAliases(context, from)) {
            alias.setEPerson(to);
            alias.setSource(ClarinIdentityService.SOURCE_MERGE);
            ePersonNetidAliasDAO.save(context, alias);
            count++;
        }
        return count;
    }

    /**
     * Tombstone the `from` account: it is never deleted, only disabled. Its (denormalized)
     * netid column is left untouched (R4: no rewriting beyond what merge requires), its email
     * is parked to free the unique constraint for `to`, should an admin want it later.
     */
    private String tombstone(Context context, EPerson from, EPerson to) throws SQLException {
        from.setCanLogIn(false);
        String originalEmail = from.getEmail();
        String parkedEmail = StringUtils.isBlank(originalEmail) ? null : buildParkedEmail(from, originalEmail);
        if (parkedEmail != null) {
            from.setEmail(parkedEmail);
        }
        try {
            ePersonService.update(context, from);
        } catch (AuthorizeException e) {
            throw new IllegalStateException(e);
        }
        return parkedEmail;
    }

    /**
     * Builds a "parked" email for the tombstoned account: {@code merged-<8charsOfId>+<originalEmail>}.
     * The {@code email} column is limited to {@value #EMAIL_MAX_LENGTH} characters, so when the
     * original email is long enough to overflow that limit, the local part (before the last
     * {@code @}) is truncated and a short hash of the full original email is appended to keep the
     * result unique and still traceable back to the original address.
     */
    private String buildParkedEmail(EPerson from, String originalEmail) {
        String prefix = "merged-" + from.getID().toString().substring(0, 8) + "+";
        String candidate = prefix + originalEmail;
        if (candidate.length() <= EMAIL_MAX_LENGTH) {
            return candidate;
        }

        String hash = Integer.toHexString(originalEmail.hashCode());
        int atIndex = originalEmail.lastIndexOf('@');
        String domain = atIndex >= 0 ? originalEmail.substring(atIndex) : "";
        String localPart = atIndex >= 0 ? originalEmail.substring(0, atIndex) : originalEmail;

        // Reserve space for the prefix, the hash marker and the domain, truncate the local part
        // with whatever budget remains so the whole address stays within EMAIL_MAX_LENGTH.
        String hashMarker = "~" + hash;
        int budgetForLocalPart = EMAIL_MAX_LENGTH - prefix.length() - hashMarker.length() - domain.length();
        if (budgetForLocalPart < 0) {
            budgetForLocalPart = 0;
        }
        String truncatedLocalPart = localPart.length() > budgetForLocalPart
                ? localPart.substring(0, budgetForLocalPart) : localPart;

        String parkedEmail = prefix + truncatedLocalPart + hashMarker + domain;
        if (parkedEmail.length() > EMAIL_MAX_LENGTH) {
            // Extreme edge case (e.g. very long domain): hard-truncate as a last resort.
            parkedEmail = parkedEmail.substring(0, EMAIL_MAX_LENGTH);
        }
        return parkedEmail;
    }
}
