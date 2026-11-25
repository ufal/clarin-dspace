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

import org.dspace.content.Bitstream;
import org.dspace.core.Utils;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S3 asset store that mirrors everything it writes into the local assetstore, so a bitstream exists
 * in both places and the checksum checker can compare the two copies. Switched on by
 * {@code sync.storage.service.enabled}; with it off this store behaves exactly like
 * {@link S3BitStoreService}.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class SyncS3BitStoreService extends S3BitStoreService {

    private boolean syncEnabled = false;

    @Autowired(required = true)
    private DSBitStoreService dsBitStoreService;

    @Override
    public void init() throws IOException {
        syncEnabled = DSpaceServicesFactory.getInstance().getConfigurationService()
                .getBooleanProperty("sync.storage.service.enabled", false);
        super.init();
    }

    /**
     * Whether writes are mirrored into the local assetstore.
     *
     * @return true when the mirror is on
     */
    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    @Override
    public void put(Bitstream bitstream, InputStream in) throws IOException {
        if (!syncEnabled) {
            super.put(bitstream, in);
            return;
        }

        // The stream can only be read once and both copies need it, so it is spooled to disk first.
        File scratchFile = File.createTempFile(bitstream.getInternalId(), "s3bs");
        try {
            try (FileOutputStream fos = new FileOutputStream(scratchFile)) {
                Utils.bufferedCopy(in, fos);
            } finally {
                in.close();
            }

            try (InputStream s3Copy = new FileInputStream(scratchFile)) {
                super.put(bitstream, s3Copy);
            }

            mirrorToLocalAssetstore(bitstream, scratchFile);
        } finally {
            if (!scratchFile.delete()) {
                scratchFile.deleteOnExit();
            }
        }
    }

    @Override
    public void remove(Bitstream bitstream) throws IOException {
        super.remove(bitstream);
        if (syncEnabled) {
            dsBitStoreService.remove(bitstream);
        }
    }

    private void mirrorToLocalAssetstore(Bitstream bitstream, File source) throws IOException {
        File localFile = dsBitStoreService.getFile(bitstream);
        File parent = localFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Assetstore synchronization error: directory " + parent.getPath()
                    + " was not created");
        }

        try (InputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(localFile)) {
            Utils.bufferedCopy(fis, fos);
        }
    }
}
