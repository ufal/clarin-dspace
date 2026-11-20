/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Checks are reused between health-report runs, so each run must start clean.
 */
public class CheckTest {

    /**
     * Succeeds on the first run, fails without a JSON report on every later run.
     */
    private static class FailingAfterFirstRunCheck extends Check {
        private int runs = 0;

        @Override
        protected String run(ReportInfo ri) {
            runs++;
            if (runs == 1) {
                setReportJson(new JSONObject().put("items", 5));
                return "ok\n";
            }
            error(null, "database is down");
            return "";
        }
    }

    @Test
    public void reportDoesNotRepeatErrorsOfPreviousRun() {
        Check check = new FailingAfterFirstRunCheck();
        check.report(null);
        check.report(null);
        String secondReport = check.getReport();

        check.report(null);

        assertEquals(secondReport, check.getReport());
    }

    @Test
    public void reportDoesNotKeepJsonOfPreviousRun() {
        Check check = new FailingAfterFirstRunCheck();
        check.report(null);

        check.report(null);

        assertFalse(check.getReportJson().has("items"));
    }
}
