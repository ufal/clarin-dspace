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
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

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
        try {
            writer = new PrintWriter(file, StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Error initializing FilePrinterReporter for file: " + fileName, e);
        }
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
        // flush and close the writer to ensure all data is written to the file
        writer.flush();
        writer.close();
    }
}
