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
 *     ePIC PID handles REST API</a>.
 *
 * @author Milan Kuchtiak
 */
public interface EpicPidService {

    /**
     * Return the  URL for handle, or null if handle cannot be found.
     *
     * @param prefix        The handle prefix
     * @param suffix        The handle suffix
     * @return              The handle URL or null
     * @throws IOException If request to ePIC PID server fails
     */
    String resolveURLForHandle(String prefix, String suffix) throws IOException;

    /**
     * Return the handle created or throws Exception
     *
     * @param prefix            The handle prefix
     * @param subPrefix         The handle subPrefix
     * @param subSuffix         The handle subSuffix
     * @param url               url associated with the handle
     * @return                  The full handle String (prefix/suffix)
     * @throws IOException      If request to ePIC PID server fails
     */
    String createHandle(String prefix, String subPrefix, String subSuffix, String url) throws IOException;

    /**
     * Returns no content or throws Exception
     *
     * @param prefix            The handle prefix
     * @param suffix            The handle suffix
     * @param url               url associated with the handle
     * @throws IOException      If request to ePIC PID server fails
     */
    void updateHandle(String prefix, String suffix, String url) throws IOException;

    /**
     * Returns no content or throws Exception
     *
     * @param prefix            The handle prefix
     * @param suffix            The handle suffix
     * @throws IOException      If request to ePIC PID server fails
     */
    void deleteHandle(String prefix, String suffix) throws IOException;

    /**
     * Search handles satisfying the urlQuery
     *
     * @param prefix            The handle prefix
     * @param urlQuery          part of URL used to search handles, e.g. "www.test.com"
     * @param limit             sets the limit for response (when -1 use default, which is 1000)
     * @param page              the offset to start searching from (when -1 use default, which is 1 - first page)
     * @return                  list iof handles satisfying the URL query
     * @throws IOException      If request to ePIC PID server fails
     */
    List<Handle> search(String prefix, String urlQuery, int limit, int page) throws IOException;

    /** Count handles by URL query
     *
     * @param prefix        The handle prefix
     * @param urlQuery      URL query used to filter handles, when null all handles containing URL part are counted
     * @return              handles count
     */
    int count(String prefix, String urlQuery) throws IOException;

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
    }

}
