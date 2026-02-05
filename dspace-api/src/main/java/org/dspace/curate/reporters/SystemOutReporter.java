/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate.reporters;

import java.io.PrintWriter;

import org.apache.commons.lang3.StringUtils;
import org.dspace.curate.Reporter;

/**
 * Reporter that writes to console (System.out).
 *
 * @author Milan Kuchtiak
 */
public class SystemOutReporter implements Reporter {

    private final PrintWriter writer;

    public SystemOutReporter() {
        // we use PrintWriter to avoid auto-flush after every println,
        // which is the default behavior of System.out.println
        writer = new PrintWriter(System.out, false);
    }

    @Override
    public Appendable append(CharSequence csq) {
        writer.print(csq);
        if (!StringUtils.isBlank(csq) && !csq.toString().endsWith(System.lineSeparator())) {
            writer.println();
        }
        return this;
    }

    @Override
    public Appendable append(CharSequence csq, int start, int end) {
        writer.append(csq, start, end);
        return this;
    }

    @Override
    public Appendable append(char c) {
        writer.append(c);
        return this;
    }

    @Override
    public void close() {
        writer.flush();
    }
}
