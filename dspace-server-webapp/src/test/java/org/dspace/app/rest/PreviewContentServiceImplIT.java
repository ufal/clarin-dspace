/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.BundleBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.PreviewContentBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.PreviewContent;
import org.dspace.content.service.BitstreamFormatService;
import org.dspace.content.service.PreviewContentService;
import org.dspace.util.FileInfo;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class PreviewContentServiceImplIT extends AbstractControllerIntegrationTest {

    @Autowired
    PreviewContentService previewContentService;
    @Autowired
    BitstreamFormatService bitstreamFormatService;

    PreviewContent previewContent0;
    PreviewContent previewContent1;
    PreviewContent previewContent2;
    PreviewContent previewContent31;
    PreviewContent previewContent32;
    PreviewContent previewContent321;
    PreviewContent previewContent3;

    Bitstream bitstream1;
    Bitstream bitstream2;
    Bitstream sevenZFile;
    Bitstream tarGzFile;
    Bitstream tarXGzipFile;
    Bitstream tgzFile;
    Bitstream gzFile;
    Bitstream tarXzFile;
    Bitstream xzFile;
    Bitstream tarGzFileWithWrongExtension;
    Bitstream tarXzFileWithIncorrectMimeType;

    @Before
    public void setup() throws SQLException, AuthorizeException, IOException {
        context.turnOffAuthorisationSystem();
        // create bitstream
        Community comm = CommunityBuilder.createCommunity(context)
                .withName("Community Test")
                .build();
        Collection col = CollectionBuilder.createCollection(context, comm).withName("Collection Test").build();
        Item publicItem = ItemBuilder.createItem(context, col)
                .withTitle("Test")
                .withIssueDate("2010-10-17")
                .withAuthor("Smith, Donald")
                .withSubject("ExtraEntry")
                .build();
        Bundle bundle1 = BundleBuilder.createBundle(context, publicItem)
                .withName("Bundle Test")
                .build();
        String bitstreamContent = "ThisIsSomeDummyText";

        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream1 = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("Bitstream1 Test")
                    .withDescription("description")
                    .withMimeType("text/plain")
                    .build();
        }

        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream2 = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("Bitstream2 Test")
                    .withDescription("description")
                    .withMimeType("text/plain")
                    .build();
        }

        try (InputStream is = getClass().getResourceAsStream("assetstore/logos.7z")) {
            sevenZFile = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("7Z Archive")
                    .withDescription("7z compressed file")
                    .withMimeType("application/x-7z-compressed")
                    .build();
        }

        try (InputStream is = getClass().getResourceAsStream("assetstore/logos.tar.gz")) {
            tarGzFile = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("logos.tar.gz")
                    .withDescription("tar.gz compressed file")
                    .withMimeType("application/gzip")
                    .build();
        }

        try (InputStream is = getClass().getResourceAsStream("assetstore/logos.tar.gz")) {
            tarXGzipFile = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("logos.tar.gz")
                    .withDescription("tar.gz compressed file")
                    .withCustomMimeType("application/x-gzip")
                    .build();
        }

        try (InputStream is = getClass().getResourceAsStream("assetstore/logos.tgz")) {
            tgzFile = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("TgzFile")
                    .withDescription("tgz compressed file")
                    .withMimeType("application/x-gtar")
                    .build();
        }

        try (InputStream is = getClass().getResourceAsStream("assetstore/dspace-logo.png.gz")) {
            gzFile = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("dspace-logo.png")
                    .withDescription("gzip compressed file")
                    .withMimeType("application/gzip")
                    .build();
        }

        try (InputStream is = getClass().getResourceAsStream("assetstore/logos.tar.xz")) {
            tarXzFile = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("logos.tar.xz")
                    .withDescription("tar.xz compressed file")
                    .withMimeType("application/x-xz")
                    .build();
        }

        try (InputStream is = getClass().getResourceAsStream("assetstore/logos.xz")) {
            xzFile = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("logos.xz")
                    .withDescription("xz compressed file")
                    .withMimeType("application/x-xz")
                    .build();
        }

        try (InputStream is = getClass().getResourceAsStream("assetstore/logos.tar.gz")) {
            tarGzFileWithWrongExtension = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("TAR GZ File")
                    .withDescription("tar.gz compressed file with wrong extension")
                    .withMimeType("application/gzip")
                    .build();
        }

        try (InputStream is = getClass().getResourceAsStream("assetstore/logos.tar.xz")) {
            tarXzFileWithIncorrectMimeType = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("logos.tar.xz")
                    .withDescription("tar.xz compressed file with incorrect mime type")
                    .withMimeType("application/gzip")
                    .build();
        }

        // create content previews
        previewContent0 =  PreviewContentBuilder.createPreviewContent(context, bitstream1, "test1.txt",
                null, false, "100", null).build();
        Map<String, PreviewContent> previewContentMap1 = new HashMap<>();
        previewContentMap1.put(previewContent0.getName(), previewContent0);
        previewContent1 = PreviewContentBuilder.createPreviewContent(context, bitstream1, "", null,
                true, "0", previewContentMap1).build();
        previewContent2 = PreviewContentBuilder.createPreviewContent(context, bitstream2, "test2.txt", null,
                false, "200", previewContentMap1).build();

        previewContent31 = PreviewContentBuilder.createPreviewContent(context, sevenZFile, "dspace-logo.png", null,
                false, "8609", null).build();

        previewContent321 = PreviewContentBuilder.createPreviewContent(context, sevenZFile, "clarin-logo.png", null,
                false, "10009", null).build();

        Map<String, PreviewContent> previewContentMap32 = new HashMap<>();
        previewContentMap32.put(previewContent321.getName(), previewContent321);

        previewContent32 = PreviewContentBuilder.createPreviewContent(context, sevenZFile, "clarin", null,
                true, "0", previewContentMap32).build();

        Map<String, PreviewContent> previewContentMap2 = new HashMap<>();
        previewContentMap2.put(previewContent31.getName(), previewContent31);
        previewContentMap2.put(previewContent32.getName(), previewContent32);
        previewContent3 = PreviewContentBuilder.createPreviewContent(context, sevenZFile, "", null,
                true, "0", previewContentMap2).build();
    }

    @After
    @Override
    public void destroy() throws Exception {
        //clean all
        BitstreamBuilder.deleteBitstream(bitstream1.getID());
        PreviewContentBuilder.deletePreviewContent(previewContent0.getID());
        PreviewContentBuilder.deletePreviewContent(previewContent1.getID());
        BitstreamBuilder.deleteBitstream(bitstream2.getID());
        PreviewContentBuilder.deletePreviewContent(previewContent2.getID());

        BitstreamBuilder.deleteBitstream(sevenZFile.getID());
        PreviewContentBuilder.deletePreviewContent(previewContent31.getID());
        PreviewContentBuilder.deletePreviewContent(previewContent321.getID());
        PreviewContentBuilder.deletePreviewContent(previewContent32.getID());
        PreviewContentBuilder.deletePreviewContent(previewContent3.getID());

        BitstreamBuilder.deleteBitstream(tarGzFile.getID());

        BitstreamFormat customMimeTypeFormat = tarXGzipFile.getFormat(context);
        BitstreamBuilder.deleteBitstream(tarXGzipFile.getID());
        if (customMimeTypeFormat != null) {
            bitstreamFormatService.delete(context, customMimeTypeFormat);
        }

        BitstreamBuilder.deleteBitstream(tgzFile.getID());
        BitstreamBuilder.deleteBitstream(gzFile.getID());
        BitstreamBuilder.deleteBitstream(tarXzFile.getID());
        BitstreamBuilder.deleteBitstream(xzFile.getID());
        BitstreamBuilder.deleteBitstream(tarGzFileWithWrongExtension.getID());
        BitstreamBuilder.deleteBitstream(tarXzFileWithIncorrectMimeType.getID());

        super.destroy();
    }

    @Test
    public void testFindAll() throws Exception {
        List<PreviewContent> previewContentList = previewContentService.findAll(context);
        Assert.assertEquals(7, previewContentList.size());

        Set<Integer> ids1 = previewContentList.stream().map(PreviewContent::getID).collect(Collectors.toSet());
        Set<Integer> ids2 = Set.of(
                previewContent0.getID(),
                previewContent1.getID(),
                previewContent2.getID(),
                previewContent31.getID(),
                previewContent321.getID(),
                previewContent32.getID(),
                previewContent3.getID()
        );

        Assert.assertEquals(ids1, ids2);
    }

    @Test
    public void testFindByBitstream() throws Exception {
        List<PreviewContent> previewContentList = previewContentService.findByBitstream(context, bitstream2.getID());
        Assert.assertEquals(previewContentList.size(), 1);
        Assert.assertEquals(previewContent2.getID(), previewContentList.get(0).getID());
    }

    @Test
    public void testFindRootByBitstream() throws Exception {
        List<PreviewContent> previewContentList =
                previewContentService.getPreview(context, bitstream1);
        Assert.assertEquals(previewContentList.size(), 1);
        Assert.assertEquals(previewContent1.getID(), previewContentList.get(0).getID());
    }

    @Test
    public void testFind7zContent() throws Exception {
        List<PreviewContent> previewContentList = previewContentService.findByBitstream(context, sevenZFile.getID());
        Assert.assertEquals(4, previewContentList.size());

        // the structure of the previewContent should be the following:
        // ''                       // root dir (previewContent3)
        //   - dspace-logo.png      // file (previewContent31)
        //   - clarin               // directory (previewContent32)
        //     - dspace-logo.png    // file (previewContent321)

        Assert.assertEquals(2, previewContent3.getSubPreviewContents().size());
        Assert.assertEquals(previewContent31.getID(),
                previewContent3.getSubPreviewContents().get("dspace-logo.png").getID());
        Assert.assertEquals(previewContent32.getID(),
                previewContent3.getSubPreviewContents().get("clarin").getID());

        Assert.assertEquals(1, previewContent32.getSubPreviewContents().size());
        Assert.assertEquals(previewContent321.getID(),
                previewContent32.getSubPreviewContents().get("clarin-logo.png").getID());

        assertFileInfos(sevenZFile);
    }

    @Test
    public void testTarGzContent() throws Exception {
        assertFileInfos(tarGzFile);
    }

    @Test
    public void testTarXGzipContent() throws Exception {
        assertFileInfos(tarXGzipFile);
    }

    @Test
    public void testTgzContent() throws Exception {
        assertFileInfos(tgzFile);
    }

    @Test
    public void testGzContent() throws Exception {
        assertFileInfo(gzFile, "dspace-logo.png", 8);
    }

    @Test
    public void testTarXzContent() throws Exception {
        assertFileInfos(tarXzFile);
    }

    @Test
    public void testXzContent() throws Exception {
        assertFileInfo(xzFile, "logos", 24);
    }

    @Test
    public void testGzContentForFileWithWrongExtension() throws Exception {
        assertFileInfo(tarGzFileWithWrongExtension, "TAR GZ File", 24);
    }

    @Test
    public void testGzContentForFileWithInvalidMimetype() throws Exception {
        List<FileInfo> fileInfos = previewContentService.getFilePreviewContent(context, tarXzFileWithIncorrectMimeType);
        Assert.assertTrue(fileInfos.isEmpty());
    }

    private void assertFileInfos(Bitstream bitstream) throws Exception {
        List<FileInfo> fileInfos = previewContentService.getFilePreviewContent(context, bitstream);
        Assert.assertEquals(2, fileInfos.size());
        Assert.assertTrue(fileInfos.get(0).isDirectory);
        Assert.assertEquals("clarin", fileInfos.get(0).name);
        Assert.assertTrue(fileInfos.get(1).isDirectory);
        Assert.assertEquals("", fileInfos.get(1).name);

        Assert.assertEquals(1, fileInfos.get(0).sub.size());
        Assert.assertNotNull(fileInfos.get(0).sub.get("clarin-logo.png"));
        Assert.assertEquals("9 kB", fileInfos.get(0).sub.get("clarin-logo.png").size);

        Assert.assertEquals(1, fileInfos.get(1).sub.size());
        Assert.assertNotNull(fileInfos.get(1).sub.get("dspace-logo.png"));
        Assert.assertEquals("8 kB", fileInfos.get(1).sub.get("dspace-logo.png").size);
    }

    private void assertFileInfo(Bitstream bitstream, String fileName, int size) throws Exception {
        List<FileInfo> fileInfos = previewContentService.getFilePreviewContent(context, bitstream);
        Assert.assertEquals(1, fileInfos.size());
        Assert.assertTrue(fileInfos.get(0).isDirectory);
        Assert.assertEquals(1, fileInfos.get(0).sub.size());
        Assert.assertNotNull(fileInfos.get(0).sub.get(fileName));
        Assert.assertEquals(size + " kB", fileInfos.get(0).sub.get(fileName).size);
    }

}
