/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import javax.servlet.http.HttpServletRequest;

import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Metadata values must not add their own markup to the RefBox display text.
 */
@RunWith(MockitoJUnitRunner.class)
public class ClarinRefBoxControllerTest {
    private static final String HANDLE = "123456789/1";
    private static final String ANY_LANGUAGE = "*";

    @Mock
    private ItemService itemService;

    @Mock
    private HandleService handleService;

    @Mock
    private ConfigurationService configurationService;

    @InjectMocks
    private ClarinRefBoxController controller;

    @Test
    public void displayTextEscapesMetadataMarkup() throws Exception {
        String displayText = displayText("<b>Doe</b>", "<s>2024</s>-01-01", "<img src=x onerror=alert(1)>",
                "https://hdl.handle.net/" + HANDLE);

        assertThat(displayText, not(containsString("<b>")));
        assertThat(displayText, not(containsString("<s>")));
        assertThat(displayText, not(containsString("<img")));
        assertThat(displayText, containsString("&lt;b&gt;Doe&lt;/b&gt;"));
        assertThat(displayText, containsString("&lt;s&gt;2024&lt;/s&gt;"));
        assertThat(displayText, containsString("<i>&lt;img src=x onerror=alert(1)&gt;</i>"));
        assertThat(displayText, containsString(
                "<a href=\"https://hdl.handle.net/" + HANDLE + "\">https://hdl.handle.net/" + HANDLE + "</a>"));
    }

    @Test
    public void identifierCannotLeaveTheLink() throws Exception {
        String displayText = displayText("Doe", "2024", "Title", "https://example.org/\"><script>alert(1)</script>");

        assertThat(displayText, not(containsString("<script")));
        assertThat(displayText, containsString(
                "href=\"https://example.org/&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\""));
    }

    @Test
    public void nonHttpIdentifierIsNotLinked() throws Exception {
        String displayText = displayText("Doe", "2024", "Title", "javascript:alert(1)");

        assertThat(displayText, not(containsString("<a")));
        assertThat(displayText, containsString("javascript:alert(1)"));
    }

    private String displayText(String author, String issued, String title, String identifier) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Context context = mock(Context.class);
        Item item = mock(Item.class);
        MetadataValue authorValue = mock(MetadataValue.class);
        when(authorValue.getValue()).thenReturn(author);

        when(handleService.resolveToObject(context, HANDLE)).thenReturn(item);
        when(itemService.getMetadata(item, "dc", "contributor", "author", ANY_LANGUAGE))
                .thenReturn(List.of(authorValue));
        when(itemService.getMetadataFirstValue(item, "dc", "date", "issued", ANY_LANGUAGE)).thenReturn(issued);
        when(itemService.getMetadataFirstValue(item, "dc", "title", null, ANY_LANGUAGE)).thenReturn(title);
        when(itemService.getMetadataFirstValue(item, "dc", "identifier", "doi", ANY_LANGUAGE)).thenReturn(identifier);
        when(configurationService.getProperty("dspace.name")).thenReturn("Repository");

        try (MockedStatic<ContextUtil> contexts = mockStatic(ContextUtil.class)) {
            contexts.when(() -> ContextUtil.obtainContext(request)).thenReturn(context);
            return controller.getRefboxInfo(HANDLE, request).getBody().getDisplayText();
        }
    }
}
