/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The two defects this helper was extracted to fix are the first two tests: a backslash used to escape
 * the closing quote of the ASCII fallback, and a space used to arrive as `+`.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ContentDispositionUtilsTest {

    @Test
    public void backslashMustNotTerminateTheQuotedString() {
        String header = ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, "evil\\");

        // the fallback has to carry an escaped backslash, not a bare one that eats the closing quote
        assertTrue(header, header.contains("filename=\"evil\\\\\""));
        // and filename* must still be present and parseable
        assertTrue(header, header.contains("filename*=UTF-8''evil%5C"));
    }

    @Test
    public void spaceMustBePercentEncodedNotPlus() {
        String header = ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, "my report.pdf");

        assertTrue(header, header.contains("filename*=UTF-8''my%20report.pdf"));
        assertFalse(header, header.contains("+"));
    }

    @Test
    public void literalPlusSurvivesAsPercent2B() {
        String header = ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, "a+b.txt");

        assertTrue(header, header.contains("filename*=UTF-8''a%2Bb.txt"));
    }

    @Test
    public void quoteIsEscapedInTheFallback() {
        String header = ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, "say \"hi\".txt");

        assertTrue(header, header.contains("filename=\"say \\\"hi\\\".txt\""));
    }

    @Test
    public void crlfCannotInjectAHeader() {
        String header = ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, "a\r\nX-Evil: 1.txt");

        assertFalse(header, header.contains("\r"));
        assertFalse(header, header.contains("\n"));
    }

    @Test
    public void nonAsciiFallsBackToUnderscoresButKeepsUtf8Name() {
        String header = ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, "žluťoučký.txt");

        // only the four non-ASCII letters are replaced, one underscore each
        assertTrue(header, header.contains("filename=\"_lu_ou_k_.txt\""));
        assertTrue(header, header.contains("filename*=UTF-8''%C5%BElu%C5%A5ou%C4%8Dk%C3%BD.txt"));
    }

    @Test
    public void dispositionIsHonoured() {
        assertTrue(ContentDispositionUtils.build(ContentDispositionUtils.INLINE, "a.pdf")
                .startsWith("inline; "));
        assertTrue(ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, "a.pdf")
                .startsWith("attachment; "));
    }

    @Test
    public void plainNameRoundTrips() {
        assertEquals("attachment; filename=\"corpus.zip\"; filename*=UTF-8''corpus.zip",
                ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, "corpus.zip"));
    }

    @Test
    public void nullNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, null));
    }
}
