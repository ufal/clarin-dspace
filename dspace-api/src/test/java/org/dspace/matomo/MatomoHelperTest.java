/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.matomo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.SortedSet;
import java.util.TreeSet;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Test;

/**
 * Created by okosarko on 3.8.15.
 */
public class MatomoHelperTest {

    @Test
    public void testTransformJSONResults() throws Exception {
        SortedSet set = new TreeSet();
        set.add("1simpleItemView");
        set.add("2fullItemView");
        set.add("3downloads");
        String simple = "{\"2021-01-01\":[],\"2021-01-02\":[],\"2021-01-03\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-04\":[{\"label\":\"/1-1827\",\"nb_visits\":2,\"nb_hits\":2,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-05\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-06\":[{\"label\":\"/1-1827\",\"nb_visits\":2,\"nb_hits\":2,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-07\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":2,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-08\":[{\"label\":\"/1-1827\",\"nb_visits\":2,\"nb_hits\":2,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-09\":[],\"2021-01-10\":[],\"2021-01-11\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-12\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-13\":[{\"label\":\"/1-1827\",\"nb_visits\":3,\"nb_hits\":3,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-14\":[],\"2021-01-15\":[{\"label\":\"/1-1827\",\"nb_visits\":2,\"nb_hits\":3,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-16\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-17\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-18\":[{\"label\":\"/1-1827\",\"nb_visits\":3,\"nb_hits\":3,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-19\":[],\"2021-01-20\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-21\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-22\":[{\"label\":\"/1-1827\",\"nb_visits\":2,\"nb_hits\":3,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-23\":[],\"2021-01-24\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-25\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-26\":[{\"label\":\"/1-1827\",\"nb_visits\":3,\"nb_hits\":3,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-27\":[],\"2021-01-28\":[{\"label\":\"/1-1827\",\"nb_visits\":2,\"nb_hits\":2,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-29\":[{\"label\":\"/1-1827\",\"nb_visits\":2,\"nb_hits\":2,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-30\":[{\"label\":\"/1-1827\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827\"}],\"2021-01-31\":[]}";
        String showFull = "{\"2021-01-01\":[],\"2021-01-02\":[],\"2021-01-03\":[],\"2021-01-04\":[]," +
                "\"2021-01-05\":[],\"2021-01-06\":[{\"label\":\"/1-1827?show=full\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827?show=full\"}],\"2021-01-07\":[{\"label\":\"/1-1827?show=full\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827?show=full\"}],\"2021-01-08\":[],\"2021-01-09\":[],\"2021-01-10\":[],\"2021-01-11\":[],\"2021-01-12\":[],\"2021-01-13\":[],\"2021-01-14\":[],\"2021-01-15\":[],\"2021-01-16\":[],\"2021-01-17\":[],\"2021-01-18\":[],\"2021-01-19\":[],\"2021-01-20\":[],\"2021-01-21\":[],\"2021-01-22\":[{\"label\":\"/1-1827?show=full\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827?show=full\"}],\"2021-01-23\":[],\"2021-01-24\":[{\"label\":\"/1-1827?show=full\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827?show=full\"}],\"2021-01-25\":[],\"2021-01-26\":[],\"2021-01-27\":[],\"2021-01-28\":[{\"label\":\"/1-1827?show=full\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827?show=full\"}],\"2021-01-29\":[{\"label\":\"/1-1827?show=full\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827?show=full\"}],\"2021-01-30\":[{\"label\":\"/1-1827?show=full\",\"nb_visits\":1,\"nb_hits\":1,\"url\":\"https://lindat.mff.cuni.cz/repository/xmlui/handle/11234/1-1827?show=full\"}],\"2021-01-31\":[]}\n";
        String json = String.format("[%s, %s, {}]", simple, showFull);
        String result = MatomoHelper.transformJSONResults(set, json);
        System.out.println(result);
        final JSONParser parser = new JSONParser();
        final JSONObject jsonResult = (JSONObject)parser.parse(result);
        JSONObject response = (JSONObject) jsonResult.get("response");
        JSONObject downloads = (JSONObject) response.get("downloads");
        assertTrue(downloads.isEmpty());
        JSONObject views = (JSONObject) response.get("views");
        final long totalHits =
                Long.parseLong(
                        ((JSONObject)
                            ((JSONObject)
                                    ((JSONObject)
                                            ((JSONObject) views.get("total"))
                                                    .get("2021")).get("1")).get("29")).get("nb_hits").toString());
        assertEquals(2 + 1, totalHits);
    }

}