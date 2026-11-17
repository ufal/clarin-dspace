/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.discovery;

import static org.dspace.core.Utils.emptyIfNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.solr.common.util.ContentStreamBase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.authorize.service.ResourcePolicyService;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.GroupService;

/**
 * Construct a <code>ContentStream</code> from a <code>File</code>
 */
public class FullTextContentStreams extends ContentStreamBase {
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(FullTextContentStreams.class);

    public static final String FULLTEXT_BUNDLE = "TEXT";

    protected final Context context;
    protected List<FullTextBitstream> fullTextStreams;
    protected BitstreamService bitstreamService;
    protected AuthorizeService authorizeService;
    protected ResourcePolicyService resourcePolicyService;
    protected GroupService groupService;
    protected Group anonymousGroup;
    private boolean anonymousGroupResolved;

    public FullTextContentStreams(Context context, Item parentItem) throws SQLException {
        this.context = context;
        init(parentItem);
    }

    protected void init(Item parentItem) {
        fullTextStreams = new ArrayList<>();

        if (parentItem != null) {
            sourceInfo = parentItem.getHandle();

            //extracted full text is always extracted as plain text
            contentType = "text/plain";

            buildFullTextList(parentItem);
        }
    }

    private void buildFullTextList(Item parentItem) {
        // now get full text of any bitstreams in the TEXT bundle
        // trundle through the bundles
        List<Bundle> myBundles = parentItem.getBundles();

        for (Bundle myBundle : emptyIfNull(myBundles)) {
            if (StringUtils.equals(FULLTEXT_BUNDLE, myBundle.getName())) {
                // a-ha! grab the text out of the bitstreams
                List<Bitstream> bitstreams = myBundle.getBitstreams();
                log.debug("Processing full-text bitstreams. Item handle: " + sourceInfo);

                for (Bitstream fulltextBitstream : emptyIfNull(bitstreams)) {
                    if (fulltextBitstream == null) {
                        log.error("Found a NULL bitstream when processing full-text files: item handle:" + sourceInfo);
                        continue;
                    }

                    // Only index extracted text that anonymous users may read, so the full-text
                    // of access-restricted files is never exposed through keyword search.
                    if (!isAnonymousReadable(parentItem, fulltextBitstream)) {
                        log.debug("Skipping restricted full-text bitstream "
                                + fulltextBitstream.getSequenceID() + " for item " + sourceInfo);
                        continue;
                    }

                    fullTextStreams.add(new FullTextBitstream(sourceInfo, fulltextBitstream));

                    log.debug("Added BitStream: "
                            + fulltextBitstream.getStoreNumber() + " "
                            + fulltextBitstream.getSequenceID() + " "
                            + fulltextBitstream.getName());
                }
            }
        }
    }

    /**
     * Determine whether the extracted text of the given TEXT-bundle bitstream may be read by
     * anonymous users. The text is only indexed when anonymous READ access exists, so the
     * full-text of restricted files never becomes searchable.
     *
     * <p>The extracted-text bitstream normally receives a copy of its source file's policies, but
     * that copy is not kept in sync when the source policies change later, so the matching source
     * file in the ORIGINAL bundle is checked as well. Access is denied when either the text
     * bitstream or any of its source files is not anonymously readable. On any error the text is
     * skipped (fail closed).</p>
     */
    private boolean isAnonymousReadable(Item parentItem, Bitstream textBitstream) {
        try {
            if (!hasAnonymousRead(textBitstream)) {
                return false;
            }
            for (Bitstream source : findSourceBitstreams(parentItem, textBitstream)) {
                if (!hasAnonymousRead(source)) {
                    return false;
                }
            }
            return true;
        } catch (SQLException e) {
            log.error("Unable to check read access for full-text bitstream of item "
                    + sourceInfo + ": " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * @return true if the Anonymous group has a date-valid READ policy on the given bitstream, either
     *         directly or through a group that contains Anonymous (a READ policy on such a group also
     *         grants anonymous access).
     */
    private boolean hasAnonymousRead(Bitstream bitstream) throws SQLException {
        Group anonymous = getAnonymousGroup();
        if (anonymous == null) {
            return false;
        }
        List<ResourcePolicy> policies =
            getAuthorizeService().getPoliciesActionFilter(context, bitstream, Constants.READ);
        for (ResourcePolicy policy : policies) {
            Group policyGroup = policy.getGroup();
            if (policyGroup == null || !getResourcePolicyService().isDateValid(policy)) {
                continue;
            }
            // a policy on Anonymous, or on any group that (transitively) contains Anonymous, is readable
            // by anonymous users
            if (anonymous.equals(policyGroup)
                    || getGroupService().isParentOf(context, policyGroup, anonymous)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve the Anonymous group once and reuse it, to avoid a repeated lookup for every
     * bitstream processed during a (re)index.
     */
    private Group getAnonymousGroup() throws SQLException {
        if (!anonymousGroupResolved) {
            anonymousGroup = getGroupService().findByName(context, Group.ANONYMOUS);
            anonymousGroupResolved = true;
        }
        return anonymousGroup;
    }

    /**
     * Find the source files in the ORIGINAL bundle that produced the given extracted-text
     * bitstream. The text-extraction filter names its output after the source file with a ".txt"
     * suffix; when the name does not follow that convention no source can be resolved.
     */
    private List<Bitstream> findSourceBitstreams(Item parentItem, Bitstream textBitstream) {
        String textName = textBitstream.getName();
        if (textName == null || !textName.endsWith(".txt")) {
            return Collections.emptyList();
        }
        String sourceName = textName.substring(0, textName.length() - ".txt".length());

        List<Bitstream> sources = new ArrayList<>();
        for (Bundle bundle : emptyIfNull(parentItem.getBundles())) {
            if (StringUtils.equals(Constants.CONTENT_BUNDLE_NAME, bundle.getName())) {
                for (Bitstream bitstream : emptyIfNull(bundle.getBitstreams())) {
                    if (bitstream != null && StringUtils.equals(sourceName, bitstream.getName())) {
                        sources.add(bitstream);
                    }
                }
            }
        }
        return sources;
    }

    @Override
    public String getName() {
        return StringUtils.join(Iterables.transform(fullTextStreams, new Function<FullTextBitstream, String>() {
            @Nullable
            @Override
            public String apply(@Nullable FullTextBitstream input) {
                return input == null ? "" : input.getFileName();
            }
        }), ";");
    }

    @Override
    public Long getSize() {
        long result = 0;

        if (CollectionUtils.isNotEmpty(fullTextStreams)) {
            Iterable<Long> individualSizes = Iterables
                .transform(fullTextStreams, new Function<FullTextBitstream, Long>() {
                    @Nullable
                    @Override
                    public Long apply(@Nullable FullTextBitstream input) {
                        return input == null ? 0L : input.getSize();
                    }
                });

            for (Long size : individualSizes) {
                result += size;
            }
        }

        return result;
    }

    @Override
    public Reader getReader() throws IOException {
        return super.getReader();
    }

    @Override
    public InputStream getStream() throws IOException {
        try {
            return new SequenceInputStream(new FullTextEnumeration(fullTextStreams.iterator()));
        } catch (Exception e) {
            log.error("Unable to add full text bitstreams to SOLR for item " + sourceInfo + ": " + e.getMessage(), e);
            return new ByteArrayInputStream((e.getClass() + ": " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    public boolean isEmpty() {
        return CollectionUtils.isEmpty(fullTextStreams);
    }

    private BitstreamService getBitstreamService() {
        if (bitstreamService == null) {
            bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
        }
        return bitstreamService;
    }

    private AuthorizeService getAuthorizeService() {
        if (authorizeService == null) {
            authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
        }
        return authorizeService;
    }

    private ResourcePolicyService getResourcePolicyService() {
        if (resourcePolicyService == null) {
            resourcePolicyService = AuthorizeServiceFactory.getInstance().getResourcePolicyService();
        }
        return resourcePolicyService;
    }

    private GroupService getGroupService() {
        if (groupService == null) {
            groupService = EPersonServiceFactory.getInstance().getGroupService();
        }
        return groupService;
    }

    private class FullTextBitstream {
        private final String itemHandle;
        private final Bitstream bitstream;

        public FullTextBitstream(final String parentHandle, final Bitstream file) {
            this.itemHandle = parentHandle;
            this.bitstream = file;
        }

        public String getContentType(final Context context) throws SQLException {
            BitstreamFormat format = bitstream != null ? bitstream.getFormat(context) : null;
            return format == null ? null : StringUtils.trimToEmpty(format.getMIMEType());
        }

        public String getFileName() {
            return bitstream != null ? StringUtils.trimToEmpty(bitstream.getName()) : null;
        }

        public long getSize() {
            return bitstream != null ? bitstream.getSizeBytes() : -1;
        }

        public InputStream getInputStream() throws SQLException, IOException, AuthorizeException {
            return getBitstreamService().retrieve(context, bitstream);
        }

        public String getItemHandle() {
            return itemHandle;
        }
    }

    /**
     * {@link Enumeration} is implemented because instances of this class are
     * passed to a JDK class that requires this obsolete type.
     */
    @SuppressWarnings("JdkObsolete")
    private static class FullTextEnumeration implements Enumeration<InputStream> {

        private final Iterator<FullTextBitstream> fulltextIterator;

        public FullTextEnumeration(final Iterator<FullTextBitstream> fulltextIterator) {
            this.fulltextIterator = fulltextIterator;
        }

        @Override
        public boolean hasMoreElements() {
            return fulltextIterator.hasNext();
        }

        @Override
        public InputStream nextElement() {
            InputStream inputStream = null;
            FullTextBitstream bitstream = null;

            try {
                bitstream = fulltextIterator.next();
                inputStream = bitstream.getInputStream();
            } catch (Exception e) {
                log.warn("Unable to add full text bitstream " + (bitstream == null ? "NULL" :
                    bitstream.getFileName() + " for item " + bitstream.getItemHandle())
                             + " to SOLR:" + e.getMessage(), e);

                inputStream = new ByteArrayInputStream(
                    (e.getClass() + ": " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
            }

            return inputStream == null ? null : new SequenceInputStream(
                new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)), inputStream);
        }
    }

}

