/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.xoai.services.impl.resources.functions;

import static org.dspace.xoai.services.impl.resources.functions.StringXSLFunction.BASE;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import net.sf.saxon.s9api.ExtensionFunction;
import net.sf.saxon.s9api.ItemType;
import net.sf.saxon.s9api.OccurrenceIndicator;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.SequenceType;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmEmptySequence;
import net.sf.saxon.s9api.XdmValue;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.Arrays;

/**
 * Serves as proxy for call from XSL engine.
 *
 * @author Marian Berger (marian.berger at dataquest.sk)
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public abstract class NodeListXslFunction implements ExtensionFunction {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(NodeListXslFunction.class);

    protected abstract String getFnName();
    protected abstract List<String> getList(String param);

    @Override
    public final QName getName() {
        return new QName(BASE, getFnName());
    }

    @Override
    public final SequenceType getResultType() {
        return SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ZERO_OR_MORE);
    }

    @Override
    public final SequenceType[] getArgumentTypes() {
        return new SequenceType[]{
                SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ZERO_OR_MORE)
        };
    }

    @Override
    public final XdmValue call(XdmValue[] xdmValues) throws SaxonApiException {
        if (Objects.isNull(xdmValues) || Arrays.isNullOrContainsNull(xdmValues) || xdmValues.length == 0) {
            return XdmEmptySequence.getInstance();
        }

        String val;
        try {
            val = xdmValues[0].itemAt(0).getStringValue();
        } catch (Exception e) {
            log.warn("Empty value in call of function {}, returning empty sequence", getFnName());
            return XdmEmptySequence.getInstance();
        }

        List<String> list = getList(val);
        if (list == null || list.isEmpty()) {
            return XdmEmptySequence.getInstance();
        }

        // Convert list of strings to XdmValue using streams
        return new XdmValue(
                list.stream()
                        .map(XdmAtomicValue::new)
                        .collect(Collectors.toList())
        );
    }
}