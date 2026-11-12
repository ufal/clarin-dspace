/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.storage.bitstore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Bitstream;
import org.dspace.core.Utils;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.FileRequestBodyConfiguration;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * Override of the S3BitStoreService to store all the data also in the local assetstore.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class SyncS3BitStoreService extends S3BitStoreService {

    /**
     * log4j log
     */
    private static final Logger log = LogManager.getLogger(SyncS3BitStoreService.class);
    private boolean syncEnabled = false;

    /**
     * The uploading file is divided into parts and each part is uploaded separately. The size of the part is 50 MB.
     *
     * Settable so the multipart path can actually be exercised by a test: with the 50 MB default and a small
     * fixture the loop only ever runs once, which leaves the offset arithmetic and the last-part handling
     * unverified. S3 requires every part except the last to be at least 5 MB.
     */
    private long uploadPartSizeBytes = 50 * 1024 * 1024; // 50 MB

    /**
     * Upload large file by parts - check the checksum of every part
     */
    private boolean uploadByParts = false;

    @Autowired(required = true)
    DSBitStoreService dsBitStoreService;

    @Autowired(required = true)
    ConfigurationService configurationService;

    public SyncS3BitStoreService() {
        super();
    }

    /**
     * Define syncEnabled and uploadByParts in the constructor - this values won't be overridden by the configuration
     *
     * @param syncEnabled   if true, the file will be uploaded to the local assetstore
     * @param uploadByParts if true, the file will be uploaded by parts
     */
    public SyncS3BitStoreService(boolean syncEnabled, boolean uploadByParts) {
        super();
        this.syncEnabled = syncEnabled;
        this.uploadByParts = uploadByParts;
    }

    public void init() throws IOException {
        super.init();

        // The syncEnabled and uploadByParts could be set to true in the constructor,
        // do not override them by the configuration in this case
        if (!syncEnabled) {
            syncEnabled = configurationService.getBooleanProperty("sync.storage.service.enabled", false);
        }
        if (!uploadByParts) {
            uploadByParts = configurationService.getBooleanProperty("s3.upload.by.parts.enabled", false);
        }
    }

    @Override
    public void put(Bitstream bitstream, InputStream in) throws IOException {
        String key = getFullKey(bitstream.getInternalId());
        //Copy istream to temp file, and send the file, with some metadata
        File scratchFile = File.createTempFile(bitstream.getInternalId(), "s3bs");
        try (
                FileOutputStream fos = new FileOutputStream(scratchFile);
                // Read through a digest input stream that will work out the MD5
                DigestInputStream dis = new DigestInputStream(in, MessageDigest.getInstance(CSA));
        ) {
            Utils.bufferedCopy(dis, fos);
            in.close();

            if (uploadByParts) {
                uploadByParts(key, scratchFile);
            } else {
                uploadFluently(key, scratchFile);
            }

            bitstream.setSizeBytes(scratchFile.length());
            // we cannot use the S3 ETAG here as it could be not a MD5 in case of multipart upload (large files) or if
            // the bucket is encrypted
            bitstream.setChecksum(Utils.toHex(dis.getMessageDigest().digest()));
            bitstream.setChecksumAlgorithm(CSA);

            if (syncEnabled) {
                // Upload file into local assetstore - use buffered copy to avoid memory issues, because of large files
                File localFile = dsBitStoreService.getFile(bitstream);
                // Create a new file in the assetstore if it does not exist
                createFileIfNotExist(localFile);

                // Copy content from scratch file to local assetstore file. Both streams have to be closed -
                // leaking the output handle keeps the assetstore file locked and a later remove() silently
                // fails to delete it.
                try (FileInputStream fisScratchFile = new FileInputStream(scratchFile);
                     FileOutputStream fosLocalFile = new FileOutputStream(localFile)) {
                    Utils.bufferedCopy(fisScratchFile, fosLocalFile);
                }
            }
        } catch (CompletionException e) {
            log.error("put(" + bitstream.getInternalId() + ", is)", e.getCause());
            throw new IOException(e.getCause());
        } catch (SdkException | IOException e) {
            log.error("put(" + bitstream.getInternalId() + ", is)", e);
            throw new IOException(e);
        } catch (NoSuchAlgorithmException nsae) {
            // Should never happen
            log.warn("Caught NoSuchAlgorithmException", nsae);
        } finally {
            if (!scratchFile.delete()) {
                scratchFile.deleteOnExit();
            }
        }
    }

    @Override
    public void remove(Bitstream bitstream) throws IOException {
        // Remove file from S3 - the parent already logs and wraps the failure in an IOException
        super.remove(bitstream);
        if (syncEnabled) {
            // Remove file from local assetstore
            dsBitStoreService.remove(bitstream);
        }
    }

    /**
     * Create a new file in the assetstore if it does not exist
     *
     * @param localFile
     * @throws IOException
     */
    private void createFileIfNotExist(File localFile) throws IOException {
        if (localFile.exists()) {
            return;
        }

        // Create the necessary parent directories if they do not yet exist
        if (!localFile.getParentFile().mkdirs()) {
            throw new IOException("Assetstore synchronization error: Directories in the assetstore for the file " +
                    "with path" + localFile.getParent() + " were not created");
        }
        if (!localFile.createNewFile()) {
            throw new IOException("Assetstore synchronization error: File " + localFile.getPath() +
                    " was not created");
        }
    }

    /**
     * Upload a file fluently. The CRT client splits it into parts on its own if it is large enough.
     *
     * @param key the bitstream's internalId
     * @param scratchFile the file to upload
     */
    private void uploadFluently(String key, File scratchFile) {
        // `assetstore.s3.s3ChecksumAlgorithm` would otherwise be dead configuration for the fork:
        // bitstore.xml wires this class, which overrides put(), so the parent's putObject never runs.
        ChecksumAlgorithm algorithm = getS3ChecksumAlgorithm();
        s3AsyncClient.putObject(r -> {
            r.bucket(getBucketName()).key(key);
            if (algorithm != null) {
                r.checksumAlgorithm(algorithm);
            }
        }, AsyncRequestBody.fromFile(scratchFile)).join();
    }

    /**
     * Upload a file by parts. The file is divided into parts and each part is uploaded separately.
     * The checksum of each part is checked. If the checksum does not match, the file is not uploaded.
     *
     * @param key the bitstream's internalId
     * @param scratchFile the file to upload
     * @throws IOException if an I/O error occurs
     */
    private void uploadByParts(String key, File scratchFile) throws IOException {
        // Initialize MessageDigest for computing checksum
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }

        // Initiate multipart upload
        String uploadId = s3AsyncClient.createMultipartUpload(r -> r.bucket(getBucketName()).key(key))
                .join().uploadId();

        // Create a list to hold the ETags for individual parts
        List<CompletedPart> completedParts = new ArrayList<>();

        try {
            // Upload parts
            long fileLength = scratchFile.length();
            long remainingBytes = fileLength;
            int partNumber = 1;

            while (remainingBytes > 0) {
                long bytesToUpload = Math.min(uploadPartSizeBytes, remainingBytes);
                long offset = fileLength - remainingBytes;

                // Calculate the checksum for the part
                String partChecksum = calculatePartChecksum(scratchFile, offset, bytesToUpload, digest);

                final int currentPartNumber = partNumber;
                // A file-backed body, not a stream: the SDK re-reads it from the start when it retries a
                // part. A non-resettable stream makes any transient S3 error permanent
                // ("Request cannot be retried, because the request stream could not be reset"), which is
                // what the v1 SDK avoided by taking the file plus an offset.
                UploadPartResponse uploadPartResponse = s3AsyncClient.uploadPart(
                        r -> r.bucket(getBucketName()).key(key).uploadId(uploadId).partNumber(currentPartNumber),
                        AsyncRequestBody.fromFile(FileRequestBodyConfiguration.builder()
                                .path(scratchFile.toPath())
                                .position(offset)
                                .numBytesToRead(bytesToUpload)
                                .build())).join();

                // Collect the ETag for the part
                completedParts.add(CompletedPart.builder()
                        .partNumber(currentPartNumber)
                        .eTag(uploadPartResponse.eTag())
                        .build());

                // Compare checksums - local with ETag. Unlike the v1 SDK, v2 hands the ETag back quoted.
                String eTag = StringUtils.strip(uploadPartResponse.eTag(), "\"");
                if (!StringUtils.equals(eTag, partChecksum)) {
                    String errorMessage = "Checksums do not match error: The locally computed checksum does " +
                            "not match with the ETag from the UploadPartResult. Local checksum: " + partChecksum +
                            ", ETag: " + eTag + ", partNumber: " + currentPartNumber;
                    log.error(errorMessage);
                    throw new IOException(errorMessage);
                }

                remainingBytes -= bytesToUpload;
                partNumber++;
            }

            // Complete the multipart upload
            s3AsyncClient.completeMultipartUpload(r -> r.bucket(getBucketName()).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())).join();
        } catch (IOException e) {
            abortQuietly(key, uploadId);
            throw e;
        } catch (SdkException | CompletionException e) {
            // This used to be logged and swallowed, which let put() carry on and record a bitstream that
            // S3 does not actually hold - silent data loss. Abort so the parts are not billed forever.
            abortQuietly(key, uploadId);
            throw new IOException("Multipart upload of " + key + " failed", e);
        }
    }

    /**
     * Abort a multipart upload, reporting but not rethrowing - the caller is already failing and the
     * original cause is the one worth propagating.
     *
     * @param key the bitstream's internalId
     * @param uploadId the multipart upload to abort
     */
    private void abortQuietly(String key, String uploadId) {
        try {
            s3AsyncClient.abortMultipartUpload(
                    r -> r.bucket(getBucketName()).key(key).uploadId(uploadId)).join();
        } catch (SdkException | CompletionException e) {
            log.error("Could not abort multipart upload " + uploadId + " for " + key
                    + "; its parts will remain until a lifecycle rule removes them", e);
        }
    }

    public long getUploadPartSizeBytes() {
        return uploadPartSizeBytes;
    }

    public void setUploadPartSizeBytes(long uploadPartSizeBytes) {
        this.uploadPartSizeBytes = uploadPartSizeBytes;
    }

    /**
     * Calculate the checksum of the specified part of the file (Multipart upload)
     *
     * @param file the uploading file
     * @param offset the offset in the file
     * @param length the length of the part
     * @param digest the message digest for computing the checksum
     * @return the checksum of the part
     * @throws IOException if an I/O error occurs
     */
    public static String calculatePartChecksum(File file, long offset, long length, MessageDigest digest)
            throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             DigestInputStream dis = new DigestInputStream(fis, digest)) {
            // Skip to the specified offset. `position` is exact, unlike `skip`, which may stop short.
            fis.getChannel().position(offset);

            // Read the specified length
            IOUtils.copyLarge(dis, OutputStream.nullOutputStream(), 0, length);

            // Convert the digest to a hex string
            return Utils.toHex(digest.digest());
        }
    }
}
