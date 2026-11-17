/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.services.impl.resources.functions;

import org.dspace.utils.SpecialItemService;

/**
 * The GetAvailableFn class extends the StringXSLFunction to provide a custom function
 * that retrieves the availability status of an item based on its identifier.
 * It uses the SpecialItemService to fetch the available information.
 * This function is intended to be used in XSL transformations where the
 * "getAvailable" function is called with an item's identifier as a parameter.
 *
 * @author Michaela Paurikova(michaela.paurikova at dataquest.sk)
 */
public class GetAvailableFn extends StringXSLFunction {
    @Override
    protected String getFnName() {
        return "getAvailable";
    }

    @Override
    protected String getStringResult(String param) {
        return SpecialItemService.getAvailable(param);
    }
}
