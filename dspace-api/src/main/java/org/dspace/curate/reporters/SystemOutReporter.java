/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate.reporters;

import java.io.IOException;
import java.io.PrintWriter;

import org.apache.commons.lang3.StringUtils;
import org.dspace.curate.Reporter;

public class SystemOutReporter implements Reporter {

    PrintWriter writer;

    public SystemOutReporter() {
        // we use PrintWriter to avoid auto-flush after every println,
        // which is the default behavior of System.out.println
        writer = new PrintWriter(System.out, false);
    }

    /**
     * Reporter that writes to console (System.out).
     *
     * @author Milan Kuchtiak
     */
    @Override
    public Appendable append(CharSequence csq) throws IOException {
        if (csq == null || StringUtils.isBlank(csq.toString()) || csq.toString().endsWith(System.lineSeparator())) {
            writer.print(csq);
        } else {
            writer.println(csq);
        }
        return this;
    }

    @Override
    public Appendable append(CharSequence csq, int start, int end) throws IOException {
        writer.append(csq, start, end);
        return this;
    }

    @Override
    public Appendable append(char c) throws IOException {
        writer.append(c);
        return this;
    }

    @Override
    public void close() throws Exception {
        writer.flush();
    }
}
