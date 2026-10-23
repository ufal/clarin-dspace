/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit.step;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.dspace.app.rest.exception.ClarinLicenseNotFoundException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.patch.JsonValueEvaluator;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.ReplaceOperation;
import org.dspace.app.rest.model.step.ClarinDataLicense;
import org.dspace.app.rest.submit.AbstractProcessingStep;
import org.dspace.app.rest.submit.SubmissionService;
import org.dspace.app.util.SubmissionStepConfig;
import org.dspace.content.InProgressSubmission;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.core.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Submission step exposing the CLARIN resource license selected for the
 * in-progress submission. Data is sourced from the item's {@code dc.rights*}
 * metadata; the selection is updated via a section-scoped patch
 * {@code /sections/clarin-license/select}.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class ClarinLicenseResourceStep extends AbstractProcessingStep {

    private static final Logger log = LoggerFactory.getLogger(ClarinLicenseResourceStep.class);

    /**
     * Sub-path of the section patch used to select a CLARIN license by name,
     * e.g. {@code /sections/clarin-license/select}.
     */
    private static final String LICENSE_SELECT_OPERATION_ENTRY = "select";

    @Override
    public ClarinDataLicense getData(SubmissionService submissionService, InProgressSubmission obj,
            SubmissionStepConfig config) {
        ClarinDataLicense result = new ClarinDataLicense();
        Item item = obj.getItem();
        if (item == null) {
            return result;
        }

        List<MetadataValue> name = itemService.getMetadataByMetadataString(item, "dc.rights");
        List<MetadataValue> uri = itemService.getMetadataByMetadataString(item, "dc.rights.uri");
        List<MetadataValue> label = itemService.getMetadataByMetadataString(item, "dc.rights.label");

        if (CollectionUtils.isNotEmpty(name)) {
            result.setName(name.get(0).getValue());
        }
        if (CollectionUtils.isNotEmpty(uri)) {
            result.setDefinition(uri.get(0).getValue());
        }
        if (CollectionUtils.isNotEmpty(label)) {
            result.setLabel(label.get(0).getValue());
        }
        result.setGranted(CollectionUtils.isNotEmpty(name)
                && CollectionUtils.isNotEmpty(uri)
                && CollectionUtils.isNotEmpty(label));
        return result;
    }

    @Override
    public void doPatchProcessing(Context context, HttpServletRequest currentRequest, InProgressSubmission source,
            Operation op, SubmissionStepConfig stepConf) throws Exception {

        String path = op.getPath();

        if (path.endsWith("/" + LICENSE_SELECT_OPERATION_ENTRY)) {
            if (!(op instanceof ReplaceOperation)) {
                throw new UnprocessableEntityException(
                        "The operation '" + op.getOp() + "' is not supported for path " + path);
            }
            String licenseName = extractLicenseName(op);
            // A blank/missing license name is intentionally treated as a request
            // to clear the currently selected CLARIN license, mirroring the
            // legacy `/license` path in WorkspaceItemRestRepository.
            // {@link ClarinLicenseSubmissionUtils#applyLicense} handles a blank
            // name as a "clear" operation.
            try {
                ClarinLicenseSubmissionUtils.applyLicense(context, source.getItem(), licenseName);
            } catch (ClarinLicenseNotFoundException ex) {
                // Surface invalid client input as 422 instead of leaking as 500.
                throw new UnprocessableEntityException(ex.getMessage(), ex);
            }
            return;
        }

        if (path.endsWith(LICENSE_STEP_OPERATION_ENTRY)) {
            // `granted` patches are a no-op on this section; kept for older clients.
            log.info("Ignoring legacy '{}/granted' patch on the CLARIN license section.", stepConf.getId());
            return;
        }

        throw new UnprocessableEntityException("The path " + path + " cannot be patched");
    }

    /**
     * Extract the CLARIN license name from a JSON Patch {@link Operation}.
     * <p>
     * The submission API receives section updates as JSON Patch operations
     * (see {@code /sections/clarin-license/select}). The {@code value} field
     * of such an operation is not strongly typed: depending on the request
     * shape and how the JSON Patch payload was parsed upstream, it can arrive
     * as:
     * <ul>
     *   <li>a plain {@link String}, e.g. {@code "value": "CC-BY"};</li>
     *   <li>a {@link JsonValueEvaluator} wrapping a {@link JsonNode}, when the
     *       payload is sent as a JSON object such as
     *       {@code "value": { "value": "CC-BY" }} or as a bare textual node;</li>
     *   <li>{@code null} when the client omitted the value entirely.</li>
     * </ul>
     * This helper normalizes those cases into a single {@code String} license
     * name (or {@code null} if no usable value is present), so the rest of the
     * step can call {@link ClarinLicenseSubmissionUtils#applyLicense} with a
     * simple value and treat missing input as a client error.
     *
     * @param op the JSON Patch operation targeting the license {@code select} path
     * @return the license name extracted from the operation value, or {@code null}
     *         if the operation has no usable value (missing, null, or of an
     *         unsupported type)
     */
    private String extractLicenseName(Operation op) {
        Object value = op.getValue();
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof JsonValueEvaluator) {
            JsonNode valueNode = ((JsonValueEvaluator) value).getValueNode();
            if (valueNode == null) {
                return null;
            }
            JsonNode inner = valueNode.get("value");
            if (inner != null) {
                return inner.asText();
            }
            if (valueNode.isTextual()) {
                return valueNode.asText();
            }
        }
        log.warn("Unsupported Operation value type for license name extraction: {}",
                value.getClass().getName());
        return null;
    }
}
