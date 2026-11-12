/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.storage.bitstore.service;

import java.io.UnsupportedEncodingException;

/**
 * Service for generating presigned URLs for direct downloads from S3.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public interface S3DirectDownloadService {
    /**
     * Generate a presigned URL for downloading a file from S3.
     *
     * @param bucket             The S3 bucket name
     * @param key                The bitstream path in the S3 bucket
     * @param expirationSeconds  The number of seconds until the URL expires
     * @param bitstreamName      The name of the bitstream to be used in the Content-Disposition header
     * @return                   A string containing the presigned URL for direct download access
     */
    String generatePresignedUrl(String bucket, String key, int expirationSeconds, String bitstreamName)
            throws UnsupportedEncodingException;

    /**
     * Generate a presigned URL, serving it with a caller-supplied Content-Disposition.
     *
     * Without this the redirect always forced `attachment`, so turning on direct downloads silently
     * disabled inline preview for every format in `webui.content_disposition_inline`.
     *
     * @param bucket                      The S3 bucket name
     * @param key                         The bitstream path in the S3 bucket
     * @param expirationSeconds           The number of seconds until the URL expires
     * @param bitstreamName               The name of the bitstream, used when no override is given
     * @param contentDispositionOverride  The Content-Disposition to serve (control characters become
     *                                    spaces), or null for `attachment`
     * @return                            A string containing the presigned URL for direct download access
     */
    String generatePresignedUrl(String bucket, String key, int expirationSeconds, String bitstreamName,
                                String contentDispositionOverride) throws UnsupportedEncodingException;
}
