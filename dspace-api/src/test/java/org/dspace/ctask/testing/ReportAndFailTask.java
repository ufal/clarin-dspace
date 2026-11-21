/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.testing;

import java.io.IOException;

import org.dspace.content.DSpaceObject;
import org.dspace.curate.AbstractCurationTask;

/**
 * Curation task that reports one line and then fails.
 */
public class ReportAndFailTask extends AbstractCurationTask {

    @Override
    public int perform(DSpaceObject dso) throws IOException {
        report("Reported before failing on " + dso.getHandle());
        throw new IOException("Task failed on purpose");
    }
}
