/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.xoai.services.impl.resources.functions;

import static org.dspace.xoai.services.impl.resources.functions.StringXSLFunction.BASE;

import java.util.Arrays;
import java.util.Objects;

import net.sf.saxon.s9api.ExtensionFunction;
import net.sf.saxon.s9api.ItemType;
import net.sf.saxon.s9api.OccurrenceIndicator;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.SequenceType;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmValue;
import org.apache.logging.log4j.Logger;

/**
 * Serves as proxy for call from XSL engine.
 * @author Marian Berger (marian.berger at dataquest.sk)
 */
public abstract class ListXslFunction implements ExtensionFunction {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(ListXslFunction.class);

    protected abstract String getFnName();

    protected abstract String getStringResponse(String param);

    @Override
    final public QName getName() {
        return new QName(BASE, getFnName());
    }

    @Override
    final public SequenceType getResultType() {
        return SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ZERO_OR_ONE);
    }

    @Override
    final public SequenceType[] getArgumentTypes() {
        return new SequenceType[]{
                SequenceType.makeSequenceType(
                        ItemType.STRING, OccurrenceIndicator.ZERO_OR_MORE)};
    }

    @Override
    public final XdmValue call(XdmValue[] xdmValues) throws SaxonApiException {
        if (xdmValues == null || xdmValues.length == 0) {
            log.debug("Null or empty parameters passed to {}, returning empty string", getFnName());
            return new XdmAtomicValue("");
        }
        if (Arrays.stream(xdmValues).anyMatch(Objects::isNull)) {
            log.debug("Null or empty parameters passed to {}, returning empty string", getFnName());
            return new XdmAtomicValue("");
        }

        StringBuilder response = new StringBuilder();

        for (XdmValue arg : xdmValues) {
            if (arg == null || arg.size() == 0) {
                continue;
            }

            for (int i = 0; i < arg.size(); i++) {
                try {
                    String param = arg.itemAt(i).getStringValue();
                    String result = getStringResponse(param);
                    if (result != null) {
                        response.append(result);
                    }
                } catch (Exception e) {
                    log.warn("Error processing parameter in function {}: {}", getFnName(), e.getMessage());
                }
            }
        }

        String finalResponse = response.toString();
        log.debug("Function {} processed {} parameters and returned response of length {}",
                getFnName(), xdmValues.length, finalResponse.length());
        return new XdmAtomicValue(finalResponse);
    }
}
