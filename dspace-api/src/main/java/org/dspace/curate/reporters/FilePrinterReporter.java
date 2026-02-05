/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate.reporters;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;

import org.apache.commons.lang3.StringUtils;
import org.dspace.curate.Reporter;

/**
 * Reporter that writes to a specified file.
 *
 * @author Milan Kuchtiak
 */
public class FilePrinterReporter implements Reporter {
    private final PrintWriter writer;

    public FilePrinterReporter(String fileName) throws FileNotFoundException {
        File file = new File(fileName);
        writer = new PrintWriter(file);
    }

    @Override
    public Appendable append(CharSequence csq) {
        try {
            if (csq == null || StringUtils.isBlank(csq.toString()) || csq.toString().endsWith(System.lineSeparator())) {
                writer.print(csq);
            } else {
                writer.println(csq);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    @Override
    public Appendable append(CharSequence csq, int start, int end) {
        try {
            writer.append(csq, start, end);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    @Override
    public Appendable append(char c) {
        try {
            writer.append(c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    @Override
    public void close() throws Exception {
        writer.close();
    }
}
