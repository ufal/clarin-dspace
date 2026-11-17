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
        // strip newline from the end of the string to avoid double newlines when using println
        // do not print empty lines
        if (!StringUtils.isEmpty(csq)) {
            writer.println(StringUtils.chomp(csq.toString()));
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
        // Note: We don't close the PrintWriter to avoid closing System.out
        writer.flush();
    }
}
