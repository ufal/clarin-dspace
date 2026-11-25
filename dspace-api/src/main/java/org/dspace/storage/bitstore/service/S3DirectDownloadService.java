/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.storage.bitstore.service;

/**
 * Service for generating presigned URLs for direct downloads from S3.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public interface S3DirectDownloadService {
    /** Generate a presigned URL for the S3 object, named {@code bitstreamName}, valid 1 second to 7 days. */
    String generatePresignedUrl(String bucket, String key, int expirationSeconds, String bitstreamName);
}
