/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package cz.cuni.mff.ufal.health;


import cz.cuni.mff.ufal.dspace.IOUtils;
import org.dspace.core.ConfigurationManager;
import org.dspace.health.Check;
import org.dspace.health.ReportInfo;

import java.io.File;

public class OAIPMHCheck extends Check {

    @Override
    public String run( ReportInfo ri ) {
        String ret = "";
        String dspace_dir = ConfigurationManager.getProperty("dspace.dir");
        String dspace_url = ConfigurationManager.getProperty("dspace.baseUrl");
        String oaiurl = dspace_url + "/oai/request";
        ret += String.format("Trying [%s]\n", oaiurl);
        ret += IOUtils.run(new File(dspace_dir + "/bin/"), new String[]{
            "python", "./validators/oai_pmh/validate.py", oaiurl});
        return ret;
    }
}
