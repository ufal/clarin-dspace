/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.time.Instant;

import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

/**
 * Abstract check interface.
 *
 * @author LINDAT/CLARIN dev team
 */

public abstract class Check {

    protected static Logger log = org.apache.logging.log4j.LogManager.getLogger(Check.class);
    long took_ = -1L;
    String report_ = null;
    private JSONObject reportJson_ = new JSONObject();
    private String errors_ = "";

    // this method should be overridden
    protected abstract String run(ReportInfo ri);

    public void report(ReportInfo ri) {
        reportJson_ = new JSONObject();
        errors_ = "";
        took_ = Instant.now().toEpochMilli();
        try {
            String run_report = run(ri);
            report_ = errors_ + run_report;
        } finally {
            took_ = Instant.now().toEpochMilli() - took_;
        }
    }

    protected void error(Throwable e) {
        error(e, null);
    }

    protected void error(Throwable e, String msg) {
        errors_ += "====\nException occurred!\n";
        if (null != e) {
            errors_ += e.toString() + "\n";
            log.error("Exception during healthcheck:", e);
        }
        if (null != msg) {
            errors_ += "Reason: " + msg + "\n";
        }
    }


    /** CLARIN: accumulated human-readable report text. */
    public String getReport() {
        return report_;
    }

    /** CLARIN: structured JSON report (used by the health-report / report-diff scripts). */
    public JSONObject getReportJson() {
        return reportJson_;
    }

    public void setReportJson(JSONObject reportJson) {
        this.reportJson_ = reportJson;
    }
}
