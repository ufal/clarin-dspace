/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.handle.factory.HandleServiceFactory;
import org.dspace.handle.service.HandleService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Unit tests for parsing the uploaded CMDI in {@link SpecialItemService#getUploadedMetadata(String)}.
 */
public class SpecialItemServiceTest {

    private static final String HANDLE = "123456789/1";
    private static final String CMD_NS = "http://www.clarin.eu/cmd/";
    private static final String SECRET = "secret-file-content";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final BitstreamService bitstreamService = mock(BitstreamService.class);
    private final Bitstream bitstream = mock(Bitstream.class);

    private MockedConstruction<Context> contextConstruction;
    private MockedStatic<ContentServiceFactory> contentServiceFactory;
    private MockedStatic<HandleServiceFactory> handleServiceFactory;

    @Before
    public void setUp() throws Exception {
        Item item = mock(Item.class);
        when(item.getType()).thenReturn(Constants.ITEM);
        MetadataValue hasCmdi = mock(MetadataValue.class);
        when(hasCmdi.getValue()).thenReturn("true");
        Bundle bundle = mock(Bundle.class);
        when(bundle.getBitstreams()).thenReturn(List.of(bitstream));

        ItemService itemService = mock(ItemService.class);
        when(itemService.getMetadataByMetadataString(item, "local.hasCMDI")).thenReturn(List.of(hasCmdi));
        when(itemService.getBundles(item, "METADATA")).thenReturn(List.of(bundle));

        ContentServiceFactory csf = mock(ContentServiceFactory.class);
        when(csf.getItemService()).thenReturn(itemService);
        when(csf.getBitstreamService()).thenReturn(bitstreamService);

        HandleService handleService = mock(HandleService.class);
        when(handleService.resolveToObject(any(Context.class), eq(HANDLE))).thenReturn(item);
        HandleServiceFactory hsf = mock(HandleServiceFactory.class);
        when(hsf.getHandleService()).thenReturn(handleService);

        contextConstruction = mockConstruction(Context.class);
        contentServiceFactory = mockStatic(ContentServiceFactory.class);
        contentServiceFactory.when(ContentServiceFactory::getInstance).thenReturn(csf);
        handleServiceFactory = mockStatic(HandleServiceFactory.class);
        handleServiceFactory.when(HandleServiceFactory::getInstance).thenReturn(hsf);
    }

    @After
    public void tearDown() {
        handleServiceFactory.close();
        contentServiceFactory.close();
        contextConstruction.close();
    }

    @Test
    public void externalEntityDoesNotLeakFileContent() throws Exception {
        Path secretFile = tempFolder.newFile("secret.txt").toPath();
        Files.writeString(secretFile, SECRET);
        uploadCmdi("<?xml version=\"1.0\"?>"
                + "<!DOCTYPE CMD [<!ENTITY xxe SYSTEM \"" + secretFile.toUri() + "\">]>"
                + "<CMD xmlns=\"" + CMD_NS + "\"><Header>&xxe;</Header></CMD>");

        Element root = ((Document) SpecialItemService.getUploadedMetadata(HANDLE)).getDocumentElement();

        String text = root == null ? "" : root.getTextContent();
        assertFalse("The external entity must not be resolved", text.contains(SECRET));
        assertNull("A CMDI with a DOCTYPE must be rejected", root);
    }

    @Test
    public void normalCmdiIsStillParsed() throws Exception {
        uploadCmdi("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<CMD xmlns=\"" + CMD_NS + "\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                + " xsi:schemaLocation=\"" + CMD_NS + " http://catalog.clarin.eu/ds/ComponentRegistry/rest/"
                + "registry/profiles/clarin.eu:cr1:p_1403526079380/xsd\">"
                + "<Header><MdCreator>Tom &amp; Jerry</MdCreator></Header></CMD>");

        Element root = ((Document) SpecialItemService.getUploadedMetadata(HANDLE)).getDocumentElement();

        assertEquals(CMD_NS, root.getNamespaceURI());
        assertEquals("CMD", root.getLocalName());
        assertEquals("Tom & Jerry", root.getTextContent());
    }

    private void uploadCmdi(String xml) throws Exception {
        when(bitstreamService.retrieve(any(Context.class), eq(bitstream)))
                .thenReturn(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
