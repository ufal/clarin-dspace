/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.services.impl.resources.functions;

import static org.dspace.xoai.services.impl.resources.functions.StringXSLFunction.BASE;

import java.util.Objects;
import javax.xml.transform.dom.DOMSource;

import net.sf.saxon.s9api.ExtensionFunction;
import net.sf.saxon.s9api.ItemType;
import net.sf.saxon.s9api.OccurrenceIndicator;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.SequenceType;
import net.sf.saxon.s9api.XdmEmptySequence;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.Arrays;
import org.dspace.xoai.services.impl.resources.SharedSaxonProcessor;
import org.w3c.dom.Node;

/**
 * Serves as proxy for call from XSL engine.
 */
public abstract class NodeXslFunction implements ExtensionFunction {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(NodeXslFunction.class);

    protected abstract String getFnName();
    protected abstract Node getNode(String param);

    @Override
    public final QName getName() {
        return new QName(BASE, getFnName());
    }

    @Override
    public final SequenceType getResultType() {
        return SequenceType.makeSequenceType(ItemType.ANY_NODE, OccurrenceIndicator.ZERO_OR_ONE);
    }

    @Override
    public final SequenceType[] getArgumentTypes() {
        return new SequenceType[]{
                SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ZERO_OR_MORE)
        };
    }

    @Override
    public final XdmValue call(XdmValue[] xdmValues) throws SaxonApiException {
        if (Objects.isNull(xdmValues) || Arrays.isNullOrContainsNull(xdmValues)) {
            log.debug("Null or empty parameters passed to {}, returning empty sequence", getFnName());
            return XdmEmptySequence.getInstance();
        }

        String val;
        try {
            val = xdmValues[0].itemAt(0).getStringValue();
        } catch (Exception e) {
            log.warn("Empty value in call of function {}, returning empty sequence", getFnName());
            return XdmEmptySequence.getInstance();
        }

        Node node = getNode(val);
        if (node == null) {
            log.debug("Function {} returned null node for parameter '{}', returning empty sequence", getFnName(), val);
            return XdmEmptySequence.getInstance();
        }

        try {
            // Build XdmNode per call; DocumentBuilder is not thread-safe
            XdmNode xdmNode = SharedSaxonProcessor.getProcessor()
                    .newDocumentBuilder()
                    .build(new DOMSource(node));
            log.debug("Function {} successfully processed parameter '{}' and returned node", getFnName(), val);
            return xdmNode;

        } catch (Exception e) {
            log.error("Error in function {} processing parameter '{}': {}", getFnName(), val, e.getMessage());
            log.debug("Full stack trace for function {} error:", getFnName(), e);
            return XdmEmptySequence.getInstance();
        }
    }
}
