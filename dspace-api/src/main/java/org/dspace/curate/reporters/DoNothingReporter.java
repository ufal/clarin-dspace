/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate.reporters;

import org.dspace.curate.Reporter;

/**
 * Reporter that ignores all input.
 *
 * @author Milan Kuchtiak
 */
public class DoNothingReporter implements Reporter {

    @Override
    public Appendable append(CharSequence csq) {
        return this;
    }

    @Override
    public Appendable append(CharSequence csq, int start, int end) {
        return this;
    }

    @Override
    public Appendable append(char c) {
        return this;
    }

    @Override
    public void close() {
    }
}
