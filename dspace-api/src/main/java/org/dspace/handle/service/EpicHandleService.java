/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.handle.service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Interface to help with <a href="https://docs.pidinst.org/en/latest/epic-cookbook/handles.html" target=_new>
 *     ePIC handles REST API</a>.
 *
 * @author Milan Kuchtiak
 */
public interface EpicHandleService {

    /**
     * Returns the URL for handle, or null if handle cannot be found.
     *
     * @param prefix        The handle prefix
     * @param suffix        The handle suffix
     * @return              The handle URL or null
     * @throws IOException If request to ePIC handle server fails
     */
    String resolveURLForHandle(String prefix, String suffix) throws IOException;

    /**
     * Creates new handle with unique suffix. The suffix is prefixed with subPrefix, and suffixed with subSuffix.
     * Returns the handle created or throws Exception.
     *
     * @param prefix            The handle prefix
     * @param subPrefix         The handle subPrefix, or null
     * @param subSuffix         The handle subSuffix, or null
     * @param url               url associated with the handle (required)
     * @return                  The full handle String (prefix/suffix)
     * @throws IOException      If request to ePIC handle server fails
     */
    String createHandle(String prefix, String subPrefix, String subSuffix, String url) throws IOException;

    /**
     * Creates new handle with given prefix/suffix or updates handle when this handle already exists.
     * Returns the handle when handle is created or null when handle is updated, or throws Exception.
     *
     * @param prefix            The handle prefix
     * @param suffix            The handle suffix
     * @param url               url associated with the handle (required)
     * @return                  The full handle String (prefix/suffix) when handle is created, or null when
     *                          existing handle is updated
     * @throws IOException      If request to ePIC handle server fails
     */
    String createOrUpdateHandle(String prefix, String suffix, String url) throws IOException;

    /**
     * Returns no content or throws Exception
     *
     * @param prefix            The handle prefix
     * @param suffix            The handle suffix
     * @throws IOException      If request to ePIC handle server fails
     */
    void deleteHandle(String prefix, String suffix) throws IOException;

    /**
     * Search handles satisfying the urlQuery
     *
     * @param prefix            The handle prefix
     * @param urlQuery          part of URL used to search handles, e.g. "www.test.com"
     * @param page              the one based offset to start searching from (default is 1 - first page)
     * @param limit             sets the limit for response (default is 1000, limit = 0 - all items will be returned)
     * @return                  list of handles satisfying the URL query
     * @throws IOException      If request to ePIC handle server fails
     */
    List<Handle> searchHandles(String prefix, String urlQuery, Integer page, Integer limit) throws IOException;

    /** Count handles by URL query
     *
     * @param prefix        The handle prefix
     * @param urlQuery      URL query used to filter handles, when null all handles containing URL part are counted
     * @return              handles count
     */
    int countHandles(String prefix, String urlQuery) throws IOException;

    class Handle {
        private final String handle;
        private final String url;

        public Handle(String handle, String url) {
            this.handle = handle;
            this.url = url;
        }

        public String getHandle() {
            return handle;
        }

        public String getUrl() {
            return url;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Handle h = (Handle) o;
            return Objects.equals(handle, h.handle) && Objects.equals(url, h.url);
        }

        @Override
        public int hashCode() {
            return Objects.hash(handle, url);
        }

        @Override
        public String toString() {
            return "Handle[" + handle + " -> " + url + "]";
        }
    }

}
