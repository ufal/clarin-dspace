<?xml version="1.0" encoding="UTF-8" ?>
<!--
    CCMM (Czech Common Metadata Model) crosswalk for OAI-PMH export.
    Produces CCMM 1.1.0 dataset XML from the XOAI internal DSpace metadata format.

    CCMM schema: https://model.ccmm.cz/research-data/1.1.0/dataset/schema.xsd
                 (versioned on purpose: techlib.github.io/CCMM/ always serves the latest major)
    Namespace:   https://schema.ccmm.cz/research-data/1.1
    metadataPrefix: ccmm-xml

    See: https://github.com/techlib/CCMM
         https://github.com/ufal/clarin-dspace/issues/1145
-->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:doc="http://www.lyncode.com/xoai"
                xmlns:ccmm="https://schema.ccmm.cz/research-data/1.1"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:fn="http://custom.crosswalk.functions"
                xmlns:cw="urn:dspace:oai:ccmm-crosswalk"
                exclude-result-prefixes="doc xs fn cw"
                version="2.0">

    <xsl:output omit-xml-declaration="yes" method="xml" indent="yes" />

    <!--
        Wrong values in the metadata are published as they are stored: correcting them is the
        repository's job, not the crosswalk's.
    -->

    <!--
        A free-text URL as an IRI: the characters RFC 3986 never allows ("|", "{", "}", "^", "\",
        "`", "&lt;", "&gt;", '"') are percent-encoded; everything else, "%" included, is kept, so an
        already encoded URL is not encoded twice.
    -->
    <xsl:function name="cw:iri" as="xs:string">
        <xsl:param name="url"/>
        <xsl:sequence select="replace(replace(replace(replace(replace(replace(replace(replace(replace(
                                  normalize-space(string($url)),
                                  '\|', '%7C'), '\{', '%7B'), '\}', '%7D'), '\^', '%5E'), '\\', '%5C'),
                                  '`', '%60'), '&lt;', '%3C'), '&gt;', '%3E'), '&quot;', '%22')"/>
    </xsl:function>

    <!-- ============================================================ -->
    <!-- Fallback constants: extracted for maintainability             -->
    <!-- ============================================================ -->
    <!-- placeholders that declare themselves as such rather than inventing a plausible name -->
    <xsl:variable name="FALLBACK_REPOSITORY_NAME" select="':unav'"/>
    <xsl:variable name="FALLBACK_REPOSITORY_URL" select="'urn:x-ccmm:repository-unknown'"/>
    <xsl:variable name="FALLBACK_TITLE" select="'Untitled'"/>
    <xsl:variable name="FALLBACK_PUBLICATION_YEAR" select="'9999'"/>
    <!-- used only when an item has no subject at all and oai.ccmm.subject.default is unset -->
    <xsl:variable name="FALLBACK_SUBJECT" select="':unav'"/>

    <!-- ============================================================ -->
    <!-- EU file types.                                                -->
    <!-- CCMM (Format.label): "Use a label of a codelist value from    -->
    <!-- http://publications.europa.eu/resource/dataset/file-type."    -->
    <!-- media_type stays on IANA, which its own scope note asks for.  -->
    <!-- A media type with no EU concept keeps the IANA value, because -->
    <!-- format/iri is mandatory and an invented EU code would be      -->
    <!-- worse than a true IANA one.                                   -->
    <!-- ============================================================ -->
    <xsl:variable name="EU_FILETYPE_IRI" select="'http://publications.europa.eu/resource/authority/file-type/'"/>
    <xsl:variable name="euFileTypes">
        <t m="application/x-gzip" c="GZIP" l="GNU zip"/>
        <t m="application/gzip" c="GZIP" l="GNU zip"/>
        <t m="video/mp4" c="MPEG4" l="MPEG-4"/>
        <t m="video/quicktime" c="MOV" l="MOV"/>
        <t m="application/octet-stream" c="OCTET" l="Octet Stream"/>
        <t m="text/plain" c="TXT" l="Plain text"/>
        <t m="application/zip" c="ZIP" l="ZIP"/>
        <t m="text/xml" c="XML" l="XML"/>
        <t m="application/xml" c="XML" l="XML"/>
        <t m="application/x-tar" c="TAR" l="TAR"/>
        <t m="application/pdf" c="PDF" l="PDF"/>
        <t m="text/csv" c="CSV" l="CSV"/>
        <t m="application/x-xz" c="XZ" l="xz"/>
        <t m="audio/x-wav" c="WAV" l="WAV"/>
        <t m="audio/wav" c="WAV" l="WAV"/>
        <t m="audio/mpeg" c="MP3" l="MP3"/>
        <t m="application/vnd.openxmlformats-officedocument.wordprocessingml.document" c="DOCX" l="Word DOCX"/>
        <t m="text/html" c="HTML" l="HTML"/>
        <t m="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" c="XLSX" l="Excel XLSX"/>
        <t m="application/vnd.openxmlformats-officedocument.presentationml.presentation" c="PPTX" l="PowerPoint PPTX"/>
        <t m="application/x-bzip2" c="BZIP2" l="bzip2"/>
        <t m="application/x-rar-compressed" c="RAR" l="RAR"/>
        <t m="image/png" c="PNG" l="PNG"/>
        <t m="application/rdf+xml" c="RDF_XML" l="RDF XML"/>
        <t m="text/richtext" c="RTF" l="RTF"/>
        <t m="application/rtf" c="RTF" l="RTF"/>
        <t m="application/vnd.oasis.opendocument.spreadsheet" c="ODS" l="ODS"/>
        <t m="application/vnd.ms-excel" c="XLS" l="Excel XLS"/>
    </xsl:variable>

    <!-- ============================================================ -->
    <!-- Controlled vocabularies.                                      -->
    <!-- The registers below are the ones CCMM 1.1.0 names in its own  -->
    <!-- specification (en/dsv.ttl, skos:scopeNote per term):          -->
    <!--   ResourceType    -> vocabularies.coar-repositories.org       -->
    <!--   AccessRights    -> purl.org/coar/access_right/              -->
    <!--   AgentRole       -> vocabs.ccmm.cz/registry/codelist/AgentRole/       -->
    <!--   DateType        -> vocabs.ccmm.cz/registry/codelist/TimeReference/   -->
    <!--   DescriptionType -> vocabs.ccmm.cz/registry/codelist/DescriptionType/ -->
    <!--   LanguageSystem  -> publications.europa.eu/resource/authority/language -->
    <!-- ============================================================ -->

    <!-- ============================================================ -->
    <!-- Dates.                                                        -->
    <!-- CLARIN-DSpace records an unknown issue date as the literal    -->
    <!-- "0000" and puts the real information into                     -->
    <!-- local.approximateDate.issued, either as a range               -->
    <!-- ("cca 1930-1965") or as an enumeration ("1920, 1932").        -->
    <!-- The precedence below is the one LINDAT applies to itself in   -->
    <!-- ClarinDateService.composeItemDate (dspace-angular): when the  -->
    <!-- local field is present it REPLACES dc.date.issued.  The       -->
    <!-- original string is never rewritten, it travels along in       -->
    <!-- ccmm:date_information.                                        -->
    <!-- ============================================================ -->
    <xsl:variable name="issuedAll"
        select="/doc:metadata/doc:element[@name='dc']/doc:element[@name='date']/doc:element[@name='issued']/doc:element/doc:field[@name='value']"/>
    <xsl:variable name="issuedFirst" select="$issuedAll[not(starts-with(normalize-space(.), '0000'))][1]"/>
    <xsl:variable name="approxRaw"
        select="normalize-space(string((/doc:metadata/doc:element[@name='local']/doc:element[@name='approximateDate']/doc:element[@name='issued']/doc:element/doc:field[@name='value'])[1]))"/>
    <xsl:variable name="approxYears" select="tokenize($approxRaw, '[^0-9]+')[string-length(.) = 4]"/>
    <xsl:variable name="approxMin" select="format-number(min(for $y in $approxYears return number($y)), '0000')"/>
    <xsl:variable name="approxMax" select="format-number(max(for $y in $approxYears return number($y)), '0000')"/>
    <!--
        "cca 1930-1965" is a continuous interval; "1920, 1932" is an enumeration of two discrete
        years that the source never claims to be continuous, so only the dashed form becomes a
        time_interval.  The verbatim string travels on in ccmm:date_information either way.
    -->
    <xsl:variable name="approxIsRange"
        select="not(contains($approxRaw, ',')) and matches($approxRaw, '[0-9]{4}\s*[-–—]\s*[0-9]{4}')"/>
    <!--
        local.additional.metadata is a newline-separated blob of "Label (field_key):value" lines
        migrated from the old Drupal catalogue.  field_year is the year the resource itself was
        created, which is the only creation date DSpace holds.
    -->
    <xsl:variable name="amBlob"
        select="string-join(/doc:metadata/doc:element[@name='local']/doc:element[@name='additional']/doc:element[@name='metadata']/doc:element/doc:field[@name='value'], '&#10;')"/>
    <xsl:variable name="fieldYear"
        select="if (matches($amBlob, 'field_year\)\s*:\s*[0-9]{4}'))
                then replace($amBlob, '^.*field_year\)\s*:\s*([0-9]{4}).*$', '$1', 's')
                else ''"/>
    <!-- dc.language.iso, falling back to dc.language; only well-formed 3-letter codes survive -->
    <xsl:variable name="languageRaw"
        select="if (/doc:metadata/doc:element[@name='dc']/doc:element[@name='language']/doc:element[@name='iso']/doc:element/doc:field[@name='value'])
                then /doc:metadata/doc:element[@name='dc']/doc:element[@name='language']/doc:element[@name='iso']/doc:element/doc:field[@name='value']
                else /doc:metadata/doc:element[@name='dc']/doc:element[@name='language']/doc:element/doc:field[@name='value']"/>
    <!--
        Language codes.  LINDAT stores ISO 639-3, but stock DSpace stores 639-1 with an optional
        region subtag ("en", "en_US", "de-DE"), so the subtag is stripped and the two-letter code
        mapped to 639-3 before the EU register IRI is built.  Document order is preserved - the
        first code is the primary language - and duplicates are removed without reordering.
    -->
    <xsl:variable name="iso1to3">
        <c k="ab">abk</c><c k="af">afr</c><c k="am">amh</c><c k="ar">ara</c><c k="as">asm</c><c k="az">aze</c>
        <c k="ba">bak</c><c k="be">bel</c><c k="bg">bul</c><c k="bn">ben</c><c k="bo">bod</c><c k="br">bre</c>
        <c k="bs">bos</c><c k="ca">cat</c><c k="ce">che</c><c k="co">cos</c><c k="cs">ces</c><c k="cv">chv</c>
        <c k="cy">cym</c><c k="da">dan</c><c k="de">deu</c><c k="dv">div</c><c k="el">ell</c><c k="en">eng</c>
        <c k="eo">epo</c><c k="es">spa</c><c k="et">est</c><c k="eu">eus</c><c k="fa">fas</c><c k="fi">fin</c>
        <c k="fo">fao</c><c k="fr">fra</c><c k="fy">fry</c><c k="ga">gle</c><c k="gd">gla</c><c k="gl">glg</c>
        <c k="gu">guj</c><c k="ha">hau</c><c k="he">heb</c><c k="hi">hin</c><c k="hr">hrv</c><c k="ht">hat</c>
        <c k="hu">hun</c><c k="hy">hye</c><c k="id">ind</c><c k="is">isl</c><c k="it">ita</c><c k="ja">jpn</c>
        <c k="jv">jav</c><c k="ka">kat</c><c k="kk">kaz</c><c k="km">khm</c><c k="kn">kan</c><c k="ko">kor</c>
        <c k="ku">kur</c><c k="ky">kir</c><c k="la">lat</c><c k="lb">ltz</c><c k="lo">lao</c><c k="lt">lit</c>
        <c k="lv">lav</c><c k="mg">mlg</c><c k="mi">mri</c><c k="mk">mkd</c><c k="ml">mal</c><c k="mn">mon</c>
        <c k="mr">mar</c><c k="ms">msa</c><c k="mt">mlt</c><c k="my">mya</c><c k="nb">nob</c><c k="ne">nep</c>
        <c k="nl">nld</c><c k="nn">nno</c><c k="no">nor</c><c k="oc">oci</c><c k="or">ori</c><c k="pa">pan</c>
        <c k="pl">pol</c><c k="ps">pus</c><c k="pt">por</c><c k="ro">ron</c><c k="ru">rus</c><c k="sa">san</c>
        <c k="sd">snd</c><c k="si">sin</c><c k="sk">slk</c><c k="sl">slv</c><c k="so">som</c><c k="sq">sqi</c>
        <c k="sr">srp</c><c k="sv">swe</c><c k="sw">swa</c><c k="ta">tam</c><c k="te">tel</c><c k="tg">tgk</c>
        <c k="th">tha</c><c k="tk">tuk</c><c k="tr">tur</c><c k="tt">tat</c><c k="ug">uig</c><c k="uk">ukr</c>
        <c k="ur">urd</c><c k="uz">uzb</c><c k="vi">vie</c><c k="wo">wol</c><c k="xh">xho</c><c k="yi">yid</c>
        <c k="yo">yor</c><c k="zh">zho</c><c k="zu">zul</c>
    </xsl:variable>
    <xsl:variable name="langAll" select="for $l in $languageRaw return
        (let $b := lower-case(tokenize(replace(normalize-space($l), '-', '_'), '_')[1])
         return if (matches($b, '^[a-z]{3}$')) then $b
                else if ($iso1to3/c[@k = $b]) then normalize-space(string($iso1to3/c[@k = $b][1]))
                else ())"/>
    <xsl:variable name="languageCodes"
        select="for $i in 1 to count($langAll) return $langAll[$i][not(. = $langAll[position() &lt; $i])]"/>
    <!--
        local.additional.metadata is one blob of "Label (field_key):value" lines carried over from
        the old Drupal catalogue.  Splitting on the FIRST colon is safe: every key ends in ':' and
        contains none itself, so a URL value keeps its "//".
    -->
    <xsl:variable name="amLines" select="tokenize($amBlob, '&#10;')"/>
    <xsl:variable name="amPublications"
        select="for $l in $amLines[contains(., 'field_publications):')] return normalize-space(substring-after($l, ':'))"/>
    <xsl:variable name="amWebservice"
        select="for $l in $amLines[contains(., 'field_tool_webservice_link):')] return normalize-space(substring-after($l, ':'))"/>
    <xsl:variable name="amDoclink"
        select="for $l in $amLines[contains(., 'field_tool_document_link):')] return normalize-space(substring-after($l, ':'))"/>
    <xsl:variable name="amVersion"
        select="for $l in $amLines[contains(., 'field_tool_version):')] return normalize-space(substring-after($l, ':'))"/>
    <xsl:variable name="amEthical"
        select="for $l in $amLines[contains(., 'field_ethical_reference):')] return normalize-space(substring-after($l, ':'))"/>
    <xsl:variable name="amLangOther"
        select="for $l in $amLines[contains(., 'field_languages_other):')] return normalize-space(substring-after($l, ':'))"/>
    <xsl:variable name="amContact"
        select="for $l in $amLines[contains(., 'field_contact_person):')] return normalize-space(substring-after($l, ':'))"/>

    <!--
        Agent names.  DSpace has one string per agent and no type, so the type has to be read off
        the value.  ORGANIZATION_AGENT_PATTERN carries the corporate forms any repository produces
        and decides the type on its own, for every field but dc.contributor.author and dc.creator.
        ANVL placeholders and "et al." are not agents at all and produce no relation, which lets
        the Creator fallback in QualifiedRelations fire instead.
    -->
    <xsl:variable name="ORGANIZATION_AGENT_PATTERN"
        select="'(univ|institu|akadem|academi|(^|\P{L})[uú]stav|fondazione|s\.r\.o|gmbh|(^|[^a-z])(ltd|inc)([^a-z]|$)|archiv|mus[ez]um|society|consortium|cent(re|er|rum)|agency|association|foundation|council|library|knihovna|press|ministr|company|film|journal|news|studio|produkc|production|facult|fakult|school|college|dept|departmen|laborat|(^|[^a-z])union([^a-z]|$)|nakladatel|vydavatel)'"/>
    <xsl:variable name="creatorValues"
        select="/doc:metadata/doc:element[@name='dc']/doc:element[@name='contributor']/doc:element[@name='author']/doc:element/doc:field[@name='value']
              | /doc:metadata/doc:element[@name='dc']/doc:element[@name='creator']/doc:element/doc:field[@name='value']"/>
    <xsl:variable name="otherValues"
        select="/doc:metadata/doc:element[@name='dc']/doc:element[@name='contributor']/doc:element[@name='other']/doc:element/doc:field[@name='value']"/>
    <xsl:variable name="hasRealCreator"
        select="exists(($creatorValues, $otherValues)[not(matches(normalize-space(.), '^\(:un'))
                                                  and lower-case(normalize-space(.)) != 'et al.'
                                                  and normalize-space(.) != ''])"/>
    <!-- local.language.name is DSpace's positional English name for the same list -->
    <xsl:variable name="languageNames"
        select="/doc:metadata/doc:element[@name='local']/doc:element[@name='language']/doc:element[@name='name']/doc:element/doc:field[@name='value']"/>
    <!--
        The item's landing page - a web page that tells a reader how to get the resource.  Every
        DSpace item has one behind its Handle, and that is what access_url asks for; the bitstream
        URL belongs in download_url.
    -->
    <xsl:variable name="handleUri"
        select="(/doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[@name='uri']/doc:element/doc:field[@name='value'][contains(., 'hdl.handle.net') or contains(., '/handle/')])[1]"/>
    <xsl:variable name="handleField"
        select="(/doc:metadata/doc:element[@name='others']/doc:field[@name='handle'])[1]"/>
    <xsl:variable name="landingPage"
        select="cw:iri(if ($handleUri) then normalize-space(string($handleUri))
                       else if (normalize-space(string($handleField)) != '')
                            then concat('http://hdl.handle.net/', normalize-space(string($handleField)))
                            else '')"/>

    <!--
        Where the repository serves a bitstream, in the shape the CMDI crosswalk already publishes:
        {server}/api/core/bitstreams/handle/{handle}/{name}.  Addressing the file by the item's
        Handle and its name keeps the link valid when the bitstream's uuid changes underneath it -
        a file replaced under the same name, a restore from backup, a migration - which is what a
        harvested record has to survive.

        Two things follow from it.  The endpoint answers under dspace.server.url and returns the
        bytes itself, so it does not pass through the front end.  And it is not part of vanilla
        DSpace: a deployment without that controller resolves none of these URLs.

        The server URL is read from the configuration, as lindat_cmdi.xsl does, not derived from
        the UI URL: the backend does not have to live under {ui}/server.
    -->
    <xsl:variable name="repoUrlRaw"
        select="normalize-space((/doc:metadata/doc:element[@name='repository']/doc:field[@name='url'])[1])"/>
    <xsl:variable name="serverUrl" select="normalize-space(string(fn:getProperty('dspace.server.url')))"/>
    <!--
        Only an absolute http(s) server URL can carry a resolvable route; anything else (a bare
        host, a relative path, an unset property) would put a non-resolvable IRI into
        download_url.  In that case the crosswalk keeps whatever URL XOAI supplied rather than
        publishing one it made up.
    -->
    <xsl:variable name="downloadBase"
        select="if (matches($serverUrl, '^https?://\S+$'))
                then concat(replace($serverUrl, '/+$', ''), '/api/core/bitstreams/handle/')
                else ''"/>
    <!-- the bare Handle the endpoint takes, from whichever of the two places carries it -->
    <xsl:variable name="itemHandle"
        select="if (normalize-space(string($handleField)) != '')
                then normalize-space(string($handleField))
                else if ($handleUri and contains(string($handleUri), 'hdl.handle.net/'))
                     then substring-after(normalize-space(string($handleUri)), 'hdl.handle.net/')
                else ''"/>

    <xsl:variable name="accessionedAll"
        select="/doc:metadata/doc:element[@name='dc']/doc:element[@name='date']/doc:element[@name='accessioned']/doc:element/doc:field[@name='value']"/>
    <xsl:variable name="availableAll"
        select="/doc:metadata/doc:element[@name='dc']/doc:element[@name='date']/doc:element[@name='available']/doc:element/doc:field[@name='value']"/>
    <!--
        The single year that publication_year and the Issued time reference must agree on.
        The year is the first four-digit run in the value, not its first four characters, so
        "[2024]", "c. 2024" and "May 2024" all yield a valid xs:gYear and "n.d." yields none.
        An exact dc.date.issued outranks an approximation: where a record carries both, the
        approximate field enumerates the years of the content and dc.date.issued is the release.
    -->
    <xsl:variable name="issuedYear"
        select="tokenize(normalize-space(string($issuedFirst)), '[^0-9]+')[matches(., '^[0-9]{4}$')][1]"/>
    <xsl:variable name="accessionedYear"
        select="tokenize(normalize-space(string($accessionedAll[1])), '[^0-9]+')[matches(., '^[0-9]{4}$')][1]"/>
    <xsl:variable name="publicationYear">
        <xsl:choose>
            <xsl:when test="$issuedYear"><xsl:value-of select="$issuedYear"/></xsl:when>
            <!-- a range is published from its start, an enumeration from its latest attested year;
                 either way this is the first year the Issued time reference states -->
            <xsl:when test="$approxIsRange and $approxMin != $approxMax"><xsl:value-of select="$approxMin"/></xsl:when>
            <xsl:when test="count($approxYears) &gt; 0"><xsl:value-of select="$approxMax"/></xsl:when>
            <xsl:when test="$accessionedYear"><xsl:value-of select="$accessionedYear"/></xsl:when>
            <xsl:otherwise><xsl:value-of select="$FALLBACK_PUBLICATION_YEAR"/></xsl:otherwise>
        </xsl:choose>
    </xsl:variable>

    <!-- Main template -->
    <xsl:template match="/">
        <ccmm:dataset xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xsi:schemaLocation="https://schema.ccmm.cz/research-data/1.1 https://model.ccmm.cz/research-data/1.1.0/dataset/schema.xsd">

            <!-- iri (optional) - the dataset's own resolvable identity, which is its landing page -->
            <xsl:if test="$landingPage != ''">
                <ccmm:iri><xsl:value-of select="$landingPage"/></ccmm:iri>
            </xsl:if>

            <!-- metadata_identification (required, unbounded) -->
            <xsl:call-template name="MetadataIdentification"/>

            <!-- identifier (required, unbounded) -->
            <xsl:call-template name="Identifiers"/>

            <!-- version (optional) -->
            <xsl:call-template name="Version"/>

            <!-- title (required) -->
            <xsl:call-template name="Title"/>

            <!-- alternate_title (optional, unbounded) -->
            <xsl:call-template name="AlternateTitles"/>

            <!-- qualified_relation (optional, unbounded) - creators, contributors -->
            <xsl:call-template name="QualifiedRelations"/>

            <!-- publication_year (required) -->
            <xsl:call-template name="PublicationYear"/>

            <!-- time_reference (required, unbounded) -->
            <xsl:call-template name="TimeReferences"/>

            <!-- resource_type (optional) -->
            <xsl:call-template name="ResourceType"/>

            <!-- primary_language (optional) -->
            <xsl:call-template name="PrimaryLanguage"/>

            <!-- other_language (optional, unbounded) -->
            <xsl:call-template name="OtherLanguages"/>

            <!-- terms_of_use (required) -->
            <xsl:call-template name="TermsOfUse"/>

            <!-- subject (required, unbounded) -->
            <xsl:call-template name="Subjects"/>

            <!-- description (optional, unbounded) -->
            <xsl:call-template name="Descriptions"/>

            <!-- location (optional, unbounded) -->
            <xsl:call-template name="Locations"/>

            <!-- funding_reference (optional, unbounded) -->
            <xsl:call-template name="FundingReferences"/>

            <!-- built before the related resources, which must not repeat a distribution -->
            <xsl:variable name="distributions" as="element()*">
                <xsl:call-template name="Distributions"/>
            </xsl:variable>

            <!-- related_resource (optional, unbounded) -->
            <xsl:call-template name="RelatedResources">
                <xsl:with-param name="distributionIris" as="xs:string*"
                    select="$distributions//(ccmm:download_url | ccmm:access_service | ccmm:endpoint_url)/ccmm:iri/string()"/>
            </xsl:call-template>

            <!-- distribution (optional, unbounded) -->
            <xsl:sequence select="$distributions"/>

            <!-- validation_result (optional, unbounded) -->
            <xsl:call-template name="ValidationResults"/>

            <!-- provenance (optional, unbounded) -->
            <xsl:call-template name="Provenances"/>

        </ccmm:dataset>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- metadata_identification                                       -->
    <!-- ============================================================ -->
    <xsl:template name="MetadataIdentification">
        <ccmm:metadata_identification>
            <!-- the OAI identifier of this very record; dsv.ttl asks for it explicitly -->
            <xsl:if test="doc:metadata/doc:element[@name='others']/doc:field[@name='identifier']">
                <ccmm:iri><xsl:value-of select="normalize-space((doc:metadata/doc:element[@name='others']/doc:field[@name='identifier'])[1])"/></ccmm:iri>
            </xsl:if>
            <!-- qualified_relation for metadata record - use repository as curator -->
            <ccmm:qualified_relation>
                <ccmm:relation>
                    <ccmm:organization>
                        <ccmm:name>
                            <xsl:choose>
                                <xsl:when test="doc:metadata/doc:element[@name='repository']/doc:field[@name='name']">
                                    <xsl:value-of select="normalize-space((doc:metadata/doc:element[@name='repository']/doc:field[@name='name'])[1])"/>
                                </xsl:when>
                                <xsl:otherwise><xsl:value-of select="$FALLBACK_REPOSITORY_NAME"/></xsl:otherwise>
                            </xsl:choose>
                        </ccmm:name>
                    </ccmm:organization>
                </ccmm:relation>
                <ccmm:role>
                    <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/AgentRole/Contributor/DataManager</ccmm:iri>
                    <ccmm:label xml:lang="en">Data Manager</ccmm:label>
                </ccmm:role>
            </ccmm:qualified_relation>
            <!--
                date_created: when this description entered the repository.  others/lastModifyDate
                is deliberately not used for date_updated - on this corpus it is a bulk re-index
                stamp, not a statement about the description changing.
            -->
            <xsl:if test="$accessionedYear">
                <ccmm:date_created>
                    <xsl:variable name="a" select="normalize-space(string($accessionedAll[1]))"/>
                    <xsl:value-of select="if ($a castable as xs:date) then $a
                                          else if (substring($a, 1, 10) castable as xs:date) then substring($a, 1, 10)
                                          else concat($accessionedYear, '-01-01')"/>
                </ccmm:date_created>
            </xsl:if>
            <!-- conforms_to_standard -->
            <ccmm:conforms_to_standard>
                <!-- the profile identifier CCMM uses for itself in its own 1.1.0 samples
                     (_metadata-samples/xml), not the XML namespace -->
                <ccmm:iri>https://model.ccmm.cz/research-data/1.1.0</ccmm:iri>
                <ccmm:label xml:lang="en">CCMM RD 1.1.0</ccmm:label>
            </ccmm:conforms_to_standard>
            <!-- original_repository -->
            <ccmm:original_repository>
                <ccmm:iri>
                    <xsl:choose>
                        <!--
                            XOAI carries the repository's own base URL; use it.  Deriving it from
                            the item URI only works for /handle/ style URLs and otherwise names the
                            item itself as the repository.
                        -->
                        <xsl:when test="matches($repoUrlRaw, '^https?://\S+$')">
                            <xsl:value-of select="$repoUrlRaw"/>
                        </xsl:when>
                        <!--
                            A DSpace-style item URL carries the repository base before /handle/.
                            An hdl.handle.net URL carries no repository at all, so it resolves to
                            the Handle system rather than being mistaken for the repository itself.
                        -->
                        <xsl:when test="doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[@name='uri']/doc:element/doc:field[@name='value'][contains(., '/handle/')]">
                            <xsl:value-of select="substring-before(normalize-space((doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[@name='uri']/doc:element/doc:field[@name='value'][contains(., '/handle/')])[1]), '/handle/')"/>
                        </xsl:when>
                        <xsl:otherwise><xsl:value-of select="$FALLBACK_REPOSITORY_URL"/></xsl:otherwise>
                    </xsl:choose>
                </ccmm:iri>
                <xsl:if test="doc:metadata/doc:element[@name='repository']/doc:field[@name='url']
                          and doc:metadata/doc:element[@name='repository']/doc:field[@name='name']">
                    <ccmm:label xml:lang="en">
                        <xsl:value-of select="normalize-space((doc:metadata/doc:element[@name='repository']/doc:field[@name='name'])[1])"/>
                    </ccmm:label>
                </xsl:if>
            </ccmm:original_repository>
        </ccmm:metadata_identification>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- identifier                                                    -->
    <!-- ============================================================ -->
    <xsl:template name="Identifiers">
        <!-- Handle identifier -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[@name='uri']/doc:element/doc:field[@name='value']">
            <xsl:if test="contains(., 'hdl.handle.net') or contains(., '/handle/')">
                <xsl:call-template name="EmitIdentifier"/>
            </xsl:if>
        </xsl:for-each>
        <!-- DOI identifier -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[@name='doi']/doc:element/doc:field[@name='value']">
            <xsl:call-template name="EmitIdentifier"/>
        </xsl:for-each>
        <!-- Other URI identifiers (non-handle) -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[@name='uri']/doc:element/doc:field[@name='value']">
            <xsl:if test="not(contains(., 'hdl.handle.net')) and not(contains(., '/handle/'))">
                <xsl:call-template name="EmitIdentifier"/>
            </xsl:if>
        </xsl:for-each>
        <!--
            Every remaining dc.identifier qualifier - dc.identifier.other and any local one.
            A value that merely repeats the item URI is skipped; dc.identifier.citation is a
            citation of the describing paper, not an identifier of the dataset, and is routed to
            related_resource instead.
        -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[not(@name = ('uri', 'doi', 'citation'))]/doc:element/doc:field[@name='value']">
            <xsl:if test="not(normalize-space(.) = ../../../doc:element[@name='uri']/doc:element/doc:field[@name='value']/normalize-space(.))
                      and not(normalize-space(.) = ../../../doc:element[@name='doi']/doc:element/doc:field[@name='value']/normalize-space(.))">
                <xsl:call-template name="EmitIdentifier"/>
            </xsl:if>
        </xsl:for-each>
        <!--
            Fallback: use handle from 'others' section if no Handle or DOI
            was found from dc.identifier.uri/doi. This covers cases where
            dc.identifier.uri exists but contains non-Handle URIs.
        -->
        <xsl:if test="not(doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[@name='uri']/doc:element/doc:field[@name='value'][contains(., 'hdl.handle.net') or contains(., '/handle/')]) and not(doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[@name='doi']/doc:element/doc:field[@name='value'])">
            <xsl:if test="doc:metadata/doc:element[@name='others']/doc:field[@name='handle']">
                <xsl:variable name="h" select="normalize-space((doc:metadata/doc:element[@name='others']/doc:field[@name='handle'])[1])"/>
                <ccmm:identifier>
                    <ccmm:iri><xsl:value-of select="concat('http://hdl.handle.net/', $h)"/></ccmm:iri>
                    <ccmm:value><xsl:value-of select="$h"/></ccmm:value>
                    <ccmm:scheme>
                        <ccmm:iri>https://hdl.handle.net/</ccmm:iri>
                        <ccmm:label xml:lang="en">Handle</ccmm:label>
                    </ccmm:scheme>
                </ccmm:identifier>
            </xsl:if>
        </xsl:if>
        <!-- identifier is 1..unbounded; a record with no identifier at all falls back to the
             OAI identifier, which every XOAI record carries -->
        <xsl:if test="not(doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element/doc:field[@name='value'])
                  and not(doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element/doc:element/doc:field[@name='value'])
                  and not(doc:metadata/doc:element[@name='others']/doc:field[@name='handle'])">
            <ccmm:identifier>
                <ccmm:value><xsl:value-of select="normalize-space((doc:metadata/doc:element[@name='others']/doc:field[@name='identifier'], doc:metadata/doc:element[@name='others']/doc:field[@name='itemId'], 'urn:x-ccmm:unidentified')[1])"/></ccmm:value>
                <ccmm:scheme>
                    <ccmm:iri>https://www.iana.org/assignments/uri-schemes</ccmm:iri>
                    <ccmm:label xml:lang="en">URI</ccmm:label>
                </ccmm:scheme>
            </ccmm:identifier>
        </xsl:if>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- version (optional)                                            -->
    <!-- ============================================================ -->
    <xsl:template name="Version">
        <xsl:choose>
            <xsl:when test="doc:metadata/doc:element[@name='dc']/doc:element[@name='description']/doc:element[@name='version']/doc:element/doc:field[@name='value']">
                <ccmm:version>
                    <xsl:value-of select="normalize-space((doc:metadata/doc:element[@name='dc']/doc:element[@name='description']/doc:element[@name='version']/doc:element/doc:field[@name='value'])[1])"/>
                </ccmm:version>
            </xsl:when>
            <xsl:when test="normalize-space($amVersion[1]) != ''">
                <ccmm:version><xsl:value-of select="normalize-space($amVersion[1])"/></ccmm:version>
            </xsl:when>
        </xsl:choose>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- title (required)                                              -->
    <!-- ============================================================ -->
    <xsl:template name="Title">
        <ccmm:title>
            <xsl:choose>
                <!-- title is 1..1; a second value is an alternate title, never part of the first -->
                <xsl:when test="doc:metadata/doc:element[@name='dc']/doc:element[@name='title']/doc:element/doc:field[@name='value']">
                    <xsl:value-of select="normalize-space((doc:metadata/doc:element[@name='dc']/doc:element[@name='title']/doc:element/doc:field[@name='value'])[1])"/>
                </xsl:when>
                <xsl:otherwise><xsl:value-of select="$FALLBACK_TITLE"/></xsl:otherwise>
            </xsl:choose>
        </ccmm:title>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- alternate_title (optional)                                    -->
    <!-- ============================================================ -->
    <xsl:template name="AlternateTitles">
        <!--
            title is 1..1, so every dc.title after the first is an alternate title.  The sequence
            has to be bound first: position() counts within each parent, and every XOAI language
            wrapper holds exactly one field, so a predicate on the step itself is always 1.
        -->
        <xsl:variable name="titleFields"
            select="doc:metadata/doc:element[@name='dc']/doc:element[@name='title']/doc:element/doc:field[@name='value']"/>
        <xsl:for-each select="$titleFields[position() &gt; 1]">
            <ccmm:alternate_title>
                <ccmm:title><xsl:call-template name="XmlLangAttribute"/><xsl:value-of select="normalize-space(.)"/></ccmm:title>
                <xsl:call-template name="AlternativeTitleType"/>
            </ccmm:alternate_title>
        </xsl:for-each>
        <!-- the META-SHARE resource name, where it differs from dc.title -->
        <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#IdentificationInfo']/doc:element[@name='resourceName']/doc:element/doc:field[@name='value']">
            <xsl:if test="normalize-space(.) != ''
                      and not(normalize-space(.) = /doc:metadata/doc:element[@name='dc']/doc:element[@name='title']/doc:element/doc:field[@name='value']/normalize-space(.))">
                <ccmm:alternate_title>
                    <ccmm:title><xsl:call-template name="XmlLangAttribute"/><xsl:value-of select="normalize-space(.)"/></ccmm:title>
                    <xsl:call-template name="AlternativeTitleType"/>
                </ccmm:alternate_title>
            </xsl:if>
        </xsl:for-each>
        <!-- dc.title.alternative mapped to alternate_title -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='title']/doc:element[@name='alternative']/doc:element/doc:field[@name='value']">
            <ccmm:alternate_title>
                <ccmm:title><xsl:call-template name="XmlLangAttribute"/><xsl:value-of select="."/></ccmm:title>
                <ccmm:alternate_title_type>
                    <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/AlternateTitle/TranslatedTitle</ccmm:iri>
                    <ccmm:label xml:lang="en">Translated Title</ccmm:label>
                </ccmm:alternate_title_type>
            </ccmm:alternate_title>
        </xsl:for-each>
    </xsl:template>

    <!--
        An alternate title that DSpace does not qualify is an alternative name for the same
        resource, which is what the register calls AlternativeTitle.  A translated title is
        typed separately above, where dc.title.alternative says so.
    -->
    <xsl:template name="AlternativeTitleType">
        <ccmm:alternate_title_type>
            <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/AlternateTitle/AlternativeTitle</ccmm:iri>
            <ccmm:label xml:lang="en">Alternative Title</ccmm:label>
        </ccmm:alternate_title_type>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- qualified_relation (creators and contributors)                -->
    <!-- ============================================================ -->
    <xsl:template name="QualifiedRelations">
        <xsl:variable name="deduped">
        <!--
            dc.contributor.author and dc.creator -> Creator.  These fields name people: a corporate
            name there is a data error the repository fixes, not one the type is guessed around.
        -->
        <xsl:call-template name="AgentRelations">
            <xsl:with-param name="values" select="$creatorValues"/>
            <xsl:with-param name="roleIri" select="'https://vocabs.ccmm.cz/registry/codelist/AgentRole/Creator'"/>
            <xsl:with-param name="roleLabel" select="'Creator'"/>
            <xsl:with-param name="alwaysPerson" select="true()"/>
        </xsl:call-template>
        <!-- dc.contributor.editor -> Editor -->
        <xsl:call-template name="AgentRelations">
            <xsl:with-param name="values" select="doc:metadata/doc:element[@name='dc']/doc:element[@name='contributor']/doc:element[@name='editor']/doc:element/doc:field[@name='value']"/>
            <xsl:with-param name="roleIri" select="'https://vocabs.ccmm.cz/registry/codelist/AgentRole/Contributor/Editor'"/>
            <xsl:with-param name="roleLabel" select="'Editor'"/>
        </xsl:call-template>
        <!--
            dc.contributor.other is an unqualified contributor, so it maps to Contributor/Other -
            except where it is the only agent the record names, in which case treating the one
            named party as the creator is the reading that matches the data.
        -->
        <xsl:call-template name="AgentRelations">
            <xsl:with-param name="values" select="$otherValues"/>
            <xsl:with-param name="roleIri" select="if (exists($creatorValues))
                then 'https://vocabs.ccmm.cz/registry/codelist/AgentRole/Contributor/Other'
                else 'https://vocabs.ccmm.cz/registry/codelist/AgentRole/Creator'"/>
            <xsl:with-param name="roleLabel" select="if (exists($creatorValues)) then 'Other' else 'Creator'"/>
        </xsl:call-template>
        <!--
            dc.publisher -> Publisher.  Publisher is a top-level role in the CCMM AgentRole
            codelist alongside Creator and Contributor; Distributor exists only below
            Contributor/ and would demote the publisher to a kind of contributor.
        -->
        <xsl:call-template name="AgentRelations">
            <xsl:with-param name="values" select="doc:metadata/doc:element[@name='dc']/doc:element[@name='publisher']/doc:element/doc:field[@name='value']"/>
            <xsl:with-param name="roleIri" select="'https://vocabs.ccmm.cz/registry/codelist/AgentRole/Publisher'"/>
            <xsl:with-param name="roleLabel" select="'Publisher'"/>
            <xsl:with-param name="preferOrganization" select="true()"/>
        </xsl:call-template>
        <!--
            CCMM asks every dataset to name a creator and a publisher.  Where the record names
            neither, an explicit ANVL "unknown" is published rather than nothing at all, so the
            absence is stated instead of being silently omitted.
        -->
        <xsl:if test="not($hasRealCreator)">
            <ccmm:qualified_relation>
                <ccmm:relation><ccmm:person><ccmm:name>:unkn</ccmm:name></ccmm:person></ccmm:relation>
                <ccmm:role>
                    <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/AgentRole/Creator</ccmm:iri>
                    <ccmm:label xml:lang="en">Creator</ccmm:label>
                </ccmm:role>
            </ccmm:qualified_relation>
        </xsl:if>
        <xsl:if test="not(doc:metadata/doc:element[@name='dc']/doc:element[@name='publisher']/doc:element/doc:field[@name='value'])">
            <ccmm:qualified_relation>
                <ccmm:relation>
                    <ccmm:organization>
                        <ccmm:name>
                            <xsl:choose>
                                <xsl:when test="doc:metadata/doc:element[@name='repository']/doc:field[@name='name']">
                                    <xsl:value-of select="normalize-space((doc:metadata/doc:element[@name='repository']/doc:field[@name='name'])[1])"/>
                                </xsl:when>
                                <xsl:otherwise><xsl:value-of select="$FALLBACK_REPOSITORY_NAME"/></xsl:otherwise>
                            </xsl:choose>
                        </ccmm:name>
                    </ccmm:organization>
                </ccmm:relation>
                <ccmm:role>
                    <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/AgentRole/Publisher</ccmm:iri>
                    <ccmm:label xml:lang="en">Publisher</ccmm:label>
                </ccmm:role>
            </ccmm:qualified_relation>
        </xsl:if>
        </xsl:variable>
        <xsl:call-template name="CopyDistinct">
            <xsl:with-param name="nodes" select="$deduped/*"/>
        </xsl:call-template>
    </xsl:template>

    <!--
        One qualified_relation per agent named in $values.  DSpace stores each agent as a value of
        its own; a legacy value that packs several ("A; B; C") is split on semicolons only.  Commas
        stay part of the name: "Family, Given" and organization names such as "Example University,
        Faculty of ..., Prague" are one agent each.
    -->
    <xsl:template name="AgentRelations">
        <xsl:param name="values"/>
        <xsl:param name="roleIri"/>
        <xsl:param name="roleLabel"/>
        <xsl:param name="preferOrganization" select="false()"/>
        <xsl:param name="alwaysPerson" select="false()"/>
        <xsl:for-each select="$values">
            <xsl:variable name="raw" select="normalize-space(.)"/>
            <xsl:variable name="agents" select="tokenize($raw, '\s*;\s*')"/>
            <xsl:for-each select="$agents">
                <xsl:variable name="v" select="normalize-space(.)"/>
                <!-- ANVL placeholders and "et al." name nobody -->
                <xsl:if test="$v != '' and not(matches($v, '^\(:un')) and lower-case($v) != 'et al.'">
                    <ccmm:qualified_relation>
                        <ccmm:relation>
                            <xsl:call-template name="AgentNode">
                                <xsl:with-param name="v" select="$v"/>
                                <xsl:with-param name="preferOrganization" select="$preferOrganization"/>
                                <xsl:with-param name="alwaysPerson" select="$alwaysPerson"/>
                            </xsl:call-template>
                        </ccmm:relation>
                        <ccmm:role>
                            <ccmm:iri><xsl:value-of select="$roleIri"/></ccmm:iri>
                            <ccmm:label xml:lang="en"><xsl:value-of select="$roleLabel"/></ccmm:label>
                        </ccmm:role>
                    </ccmm:qualified_relation>
                </xsl:if>
            </xsl:for-each>
        </xsl:for-each>
    </xsl:template>

    <!--
        A person or an organization, decided from the name itself - except for an author, who is
        always a person.  A publisher defaults to an organization unless the same string is also
        credited as an author of the same record.
        "Family, Given" - exactly one comma - additionally yields the name parts CCMM asks for.
    -->
    <xsl:template name="AgentNode">
        <xsl:param name="v"/>
        <xsl:param name="preferOrganization" select="false()"/>
        <xsl:param name="alwaysPerson" select="false()"/>
        <xsl:variable name="isNamedOrg"
            select="not($alwaysPerson) and matches($v, $ORGANIZATION_AGENT_PATTERN, 'i')"/>
        <xsl:variable name="alsoAnAuthor" select="exists($creatorValues[normalize-space(.) = $v])"/>
        <xsl:choose>
            <xsl:when test="$isNamedOrg or ($preferOrganization and not($alsoAnAuthor))">
                <ccmm:organization><ccmm:name><xsl:value-of select="$v"/></ccmm:name></ccmm:organization>
            </xsl:when>
            <xsl:otherwise>
                <ccmm:person>
                    <ccmm:name><xsl:value-of select="$v"/></ccmm:name>
                    <xsl:if test="matches($v, '^[^,]+,[^,]+$')">
                        <ccmm:given_name><xsl:value-of select="normalize-space(substring-after($v, ','))"/></ccmm:given_name>
                        <ccmm:family_name><xsl:value-of select="normalize-space(substring-before($v, ','))"/></ccmm:family_name>
                    </xsl:if>
                </ccmm:person>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- publication_year (required)                                    -->
    <!-- ============================================================ -->
    <xsl:template name="PublicationYear">
        <ccmm:publication_year><xsl:value-of select="$publicationYear"/></ccmm:publication_year>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- time_reference (required, unbounded)                          -->
    <!-- ============================================================ -->
    <!--
        xml:lang for a text value, taken from the XOAI language wrapper that encloses it.
        XOAI nests every metadata value inside an element named after its language qualifier
        ("en_US", "cs_CZ", ... or "none" when the field has no language).  Called with the
        doc:field as the context node.  Without a language the attribute is "und", the BCP 47
        code for "undetermined": the CCMM model wants every text language-tagged (its SHACL
        shapes reject untagged text), and claiming "en" would label Czech text as English.  The
        attribute itself is required by the CCMM schema.
    -->
    <xsl:template name="XmlLangAttribute">
        <xsl:variable name="wrapper" select="string(../@name)"/>
        <xsl:attribute name="xml:lang">
            <xsl:choose>
                <xsl:when test="matches($wrapper, '^[a-z]{2,3}(_[A-Za-z]{2,4})?$')">
                    <xsl:value-of select="substring-before(concat($wrapper, '_'), '_')"/>
                </xsl:when>
                <xsl:otherwise>und</xsl:otherwise>
            </xsl:choose>
        </xsl:attribute>
    </xsl:template>

    <!--
        When the issue date is published: the exact dc.date.issued if there is one, otherwise the
        approximate field.  Only a dashed approximate value ("cca 1930-1965") is a continuous
        interval; an enumeration ("1920, 1932") names discrete years and its latest is the release.
        Last resort is the repository ingest year, at year precision, because the ingest timestamp
        is not a statement about when the resource was issued.
    -->
    <xsl:template name="IssuedTemporalRepresentation">
        <ccmm:temporal_representation>
            <xsl:choose>
                <xsl:when test="$issuedYear">
                    <ccmm:time_instant>
                        <xsl:call-template name="FormatDate">
                            <xsl:with-param name="dateStr" select="$issuedFirst"/>
                        </xsl:call-template>
                    </ccmm:time_instant>
                </xsl:when>
                <xsl:when test="$approxIsRange and $approxMin != $approxMax">
                    <ccmm:time_interval>
                        <ccmm:beginning>
                            <ccmm:date><xsl:value-of select="concat($approxMin, '-01-01')"/></ccmm:date>
                        </ccmm:beginning>
                        <ccmm:end>
                            <ccmm:date><xsl:value-of select="concat($approxMax, '-12-31')"/></ccmm:date>
                        </ccmm:end>
                    </ccmm:time_interval>
                </xsl:when>
                <xsl:when test="count($approxYears) &gt; 0">
                    <ccmm:time_instant>
                        <ccmm:date><xsl:value-of select="concat($approxMax, '-01-01')"/></ccmm:date>
                    </ccmm:time_instant>
                </xsl:when>
                <xsl:when test="$accessionedYear">
                    <ccmm:time_instant>
                        <ccmm:date><xsl:value-of select="concat($accessionedYear, '-01-01')"/></ccmm:date>
                    </ccmm:time_instant>
                </xsl:when>
                <xsl:otherwise>
                    <ccmm:time_instant>
                        <ccmm:date><xsl:value-of select="concat($FALLBACK_PUBLICATION_YEAR, '-01-01')"/></ccmm:date>
                    </ccmm:time_instant>
                </xsl:otherwise>
            </xsl:choose>
        </ccmm:temporal_representation>
    </xsl:template>

    <!--
        When the resource came into being.  local.additional.metadata carries "field_year", the
        year the resource itself was created, for the digitised collections; otherwise the earliest
        attested approximate year; otherwise the issue date, which for a deposited resource is the
        closest available statement.
    -->
    <xsl:template name="CreatedTemporalRepresentation">
        <ccmm:temporal_representation>
            <xsl:choose>
                <xsl:when test="$fieldYear != ''">
                    <ccmm:time_instant>
                        <ccmm:date><xsl:value-of select="concat($fieldYear, '-01-01')"/></ccmm:date>
                    </ccmm:time_instant>
                </xsl:when>
                <xsl:when test="$approxIsRange and $approxMin != $approxMax">
                    <ccmm:time_interval>
                        <ccmm:beginning>
                            <ccmm:date><xsl:value-of select="concat($approxMin, '-01-01')"/></ccmm:date>
                        </ccmm:beginning>
                        <ccmm:end>
                            <ccmm:date><xsl:value-of select="concat($approxMax, '-12-31')"/></ccmm:date>
                        </ccmm:end>
                    </ccmm:time_interval>
                </xsl:when>
                <xsl:when test="count($approxYears) &gt; 0">
                    <ccmm:time_instant>
                        <ccmm:date><xsl:value-of select="concat($approxMin, '-01-01')"/></ccmm:date>
                    </ccmm:time_instant>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:call-template name="IssuedTemporalRepresentationInner"/>
                </xsl:otherwise>
            </xsl:choose>
        </ccmm:temporal_representation>
    </xsl:template>

    <!-- the Issued choice without its wrapper, so Created can reuse it as a last resort -->
    <xsl:template name="IssuedTemporalRepresentationInner">
        <xsl:choose>
            <xsl:when test="$issuedYear">
                <ccmm:time_instant>
                    <xsl:call-template name="FormatDate">
                        <xsl:with-param name="dateStr" select="$issuedFirst"/>
                    </xsl:call-template>
                </ccmm:time_instant>
            </xsl:when>
            <xsl:when test="$accessionedYear">
                <ccmm:time_instant>
                    <ccmm:date><xsl:value-of select="concat($accessionedYear, '-01-01')"/></ccmm:date>
                </ccmm:time_instant>
            </xsl:when>
            <xsl:otherwise>
                <ccmm:time_instant>
                    <ccmm:date><xsl:value-of select="concat($FALLBACK_PUBLICATION_YEAR, '-01-01')"/></ccmm:date>
                </ccmm:time_instant>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- time_reference (required, unbounded)                          -->
    <!-- ============================================================ -->
    <xsl:template name="TimeReferences">
        <!--
            Exactly one Issued reference: CCMM requires publication_year and the year of the
            Issued date to be the same, which several Issued references could not satisfy.
        -->
        <ccmm:time_reference>
            <xsl:call-template name="IssuedTemporalRepresentation"/>
            <ccmm:date_type>
                <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/TimeReference/Issued</ccmm:iri>
                <ccmm:label xml:lang="en">Date Issued</ccmm:label>
                <ccmm:label xml:lang="cs">Datum vydání</ccmm:label>
            </ccmm:date_type>
            <xsl:choose>
                <xsl:when test="$approxRaw != ''">
                    <ccmm:date_information xml:lang="cs"><xsl:value-of select="$approxRaw"/></ccmm:date_information>
                </xsl:when>
                <xsl:when test="not($issuedYear)">
                    <ccmm:date_information xml:lang="en">Issue date unknown; the year of the repository ingest date is used instead.</ccmm:date_information>
                </xsl:when>
            </xsl:choose>
        </ccmm:time_reference>
        <!--
            CCMM requires at least one time reference of type Created.  DSpace holds no
            creation date, so the same value is reused: for a deposited resource the date the
            depositor states is the closest available statement about when it came into being.
        -->
        <ccmm:time_reference>
            <xsl:call-template name="CreatedTemporalRepresentation"/>
            <ccmm:date_type>
                <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/TimeReference/Created</ccmm:iri>
                <ccmm:label xml:lang="en">Date Created</ccmm:label>
                <ccmm:label xml:lang="cs">Datum vytvoření</ccmm:label>
            </ccmm:date_type>
        </ccmm:time_reference>
        <!-- dc.date.accessioned -> Accepted; a value FormatDate cannot express yields no reference -->
        <xsl:for-each select="$accessionedAll[matches(normalize-space(.), '^[0-9]{4}')]">
            <ccmm:time_reference>
                <ccmm:temporal_representation>
                    <ccmm:time_instant>
                        <xsl:call-template name="FormatDate">
                            <xsl:with-param name="dateStr" select="."/>
                        </xsl:call-template>
                    </ccmm:time_instant>
                </ccmm:temporal_representation>
                <ccmm:date_type>
                    <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/TimeReference/Accepted</ccmm:iri>
                    <ccmm:label xml:lang="en">Date Accepted</ccmm:label>
                    <ccmm:label xml:lang="cs">Datum přijetí</ccmm:label>
                </ccmm:date_type>
            </ccmm:time_reference>
        </xsl:for-each>
        <!-- dc.date.available -> Available -->
        <xsl:for-each select="$availableAll[matches(normalize-space(.), '^[0-9]{4}')]">
            <ccmm:time_reference>
                <ccmm:temporal_representation>
                    <ccmm:time_instant>
                        <xsl:call-template name="FormatDate">
                            <xsl:with-param name="dateStr" select="."/>
                        </xsl:call-template>
                    </ccmm:time_instant>
                </ccmm:temporal_representation>
                <ccmm:date_type>
                    <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/TimeReference/Available</ccmm:iri>
                    <ccmm:label xml:lang="en">Date Available</ccmm:label>
                    <ccmm:label xml:lang="cs">Datum zveřejnění</ccmm:label>
                </ccmm:date_type>
            </ccmm:time_reference>
        </xsl:for-each>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- resource_type (optional)                                      -->
    <!-- ============================================================ -->
    <xsl:template name="ResourceType">
        <!--
            dc.type -> COAR Resource Type vocabulary, which CCMM names for this term.
            LINDAT values come from META-SHARE: corpus / lexicalConceptualResource /
            languageDescription are data sets, toolService is software, clip is video.
        -->
        <xsl:variable name="dctype" select="normalize-space((doc:metadata/doc:element[@name='dc']/doc:element[@name='type']/doc:element/doc:field[@name='value'])[1])"/>
        <!-- fold case once and match on a set, so the stock DSpace type list works too -->
        <xsl:variable name="t" select="lower-case($dctype)"/>
        <xsl:if test="$dctype != ''">
            <ccmm:resource_type>
                <xsl:choose>
                    <xsl:when test="$t = ('corpus', 'lexicalconceptualresource', 'languagedescription',
                                          'dataset', 'datafile/dataset', 'spreadsheet', 'language test')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_ddb1</ccmm:iri>
                        <ccmm:label xml:lang="en">dataset</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('toolservice', 'software')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_5ce6</ccmm:iri>
                        <ccmm:label xml:lang="en">software</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('clip', 'video', 'film', 'moving image')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_12ce</ccmm:iri>
                        <ccmm:label xml:lang="en">video</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('sound', 'audio')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_18cc</ccmm:iri>
                        <ccmm:label xml:lang="en">sound</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('image', 'obraz', 'still image', 'photograph')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_ecc8</ccmm:iri>
                        <ccmm:label xml:lang="en">still image</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('text', 'sentences', 'transcribed document')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_18cf</ccmm:iri>
                        <ccmm:label xml:lang="en">text</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('music notation', 'musical notation', 'musical composition')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_18cw</ccmm:iri>
                        <ccmm:label xml:lang="en">musical notation</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = 'bibliography'">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_86bc</ccmm:iri>
                        <ccmm:label xml:lang="en">bibliography</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('onlinecourse', 'learning object')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_e059</ccmm:iri>
                        <ccmm:label xml:lang="en">learning object</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = 'dashboard'">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_e9a0</ccmm:iri>
                        <ccmm:label xml:lang="en">interactive resource</ccmm:label>
                    </xsl:when>
                    <!-- the stock DSpace type list -->
                    <xsl:when test="$t = ('article', 'journal article', 'research article', 'contribution to journal')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_6501</ccmm:iri>
                        <ccmm:label xml:lang="en">journal article</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = 'book'">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_2f33</ccmm:iri>
                        <ccmm:label xml:lang="en">book</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('book chapter', 'book part')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_3248</ccmm:iri>
                        <ccmm:label xml:lang="en">book part</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = 'thesis'">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_46ec</ccmm:iri>
                        <ccmm:label xml:lang="en">thesis</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = 'doctoral thesis'">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_db06</ccmm:iri>
                        <ccmm:label xml:lang="en">doctoral thesis</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = 'master thesis'">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_bdcc</ccmm:iri>
                        <ccmm:label xml:lang="en">master thesis</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = 'bachelor thesis'">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_7a1f</ccmm:iri>
                        <ccmm:label xml:lang="en">bachelor thesis</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = 'preprint'">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_816b</ccmm:iri>
                        <ccmm:label xml:lang="en">preprint</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('report', 'technical report')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_18gh</ccmm:iri>
                        <ccmm:label xml:lang="en">technical report</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('conference paper', 'conference object')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_5794</ccmm:iri>
                        <ccmm:label xml:lang="en">conference paper</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = ('presentation', 'lecture')">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_8544</ccmm:iri>
                        <ccmm:label xml:lang="en">lecture</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$t = 'patent'">
                        <ccmm:iri>http://purl.org/coar/resource_type/c_15cd</ccmm:iri>
                        <ccmm:label xml:lang="en">patent</ccmm:label>
                    </xsl:when>
                    <xsl:otherwise>
                        <ccmm:iri>http://purl.org/coar/resource_type/c_1843</ccmm:iri>
                        <ccmm:label xml:lang="en">other</ccmm:label>
                    </xsl:otherwise>
                </xsl:choose>
            </ccmm:resource_type>
        </xsl:if>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- primary_language (optional)                                    -->
    <!-- ============================================================ -->
    <xsl:template name="PrimaryLanguage">
        <!--
            CCMM: "Use IRI identifier from the register
            http://publications.europa.eu/resource/authority/language."  The register keys on the
            uppercased 3-letter ISO 639-3 code, so the value is emitted only when it has exactly
            that shape; anything else (free text such as "English; Czech") would produce an IRI
            that does not resolve, and language_system is optional, so the element is omitted.
            The label is DSpace's own English name for the same language, held positionally in
            local.language.name; where that is absent the element carries the IRI alone.
            AJP and HBS are marked deprecated in the register but are still real, resolvable
            concepts; they are emitted unchanged rather than silently remapped (APC is a
            broadening of AJP, and HBS has no single successor).
        -->
        <xsl:variable name="langCode" select="$languageCodes[1]"/>
        <xsl:if test="$langCode">
            <!--
                The register IRI alone: the scope note asks for "language label and/or its
                3-letter code from" the register, and CCMM's own dataset-mini sample writes
                primary_language with an iri and no label.  DSpace's local.language.name is not
                the register's label - it glues words together ("UpperSorbian") and its position
                no longer lines up once duplicate codes are removed - so publishing it would be
                asserting a label the register does not use.
            -->
            <ccmm:primary_language>
                <ccmm:iri><xsl:value-of select="concat('http://publications.europa.eu/resource/authority/language/', upper-case($langCode))"/></ccmm:iri>
            </ccmm:primary_language>
        </xsl:if>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- other_language (optional)                                     -->
    <!-- ============================================================ -->
    <xsl:template name="OtherLanguages">
        <!-- every remaining code, in document order, so the positional name list stays aligned -->
        <xsl:for-each select="2 to count($languageCodes)">
            <xsl:variable name="i" select="."/>
            <ccmm:other_language>
                <ccmm:iri><xsl:value-of select="concat('http://publications.europa.eu/resource/authority/language/', upper-case($languageCodes[$i]))"/></ccmm:iri>
            </ccmm:other_language>
        </xsl:for-each>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- terms_of_use (required)                                       -->
    <!-- ============================================================ -->
    <xsl:template name="TermsOfUse">
        <!--
            access_rights says whether an anonymous user can download the files.  ItemUtils
            computes it into others/anonymousAccess from what actually gates the download - the
            Anonymous READ policy and the Clarin license confirmation of every ORIGINAL
            bitstream.  dc.rights.label is only a description of the license and decides nothing
            here.  Without the computed value the record is never published as open.
        -->
        <xsl:variable name="anonymousAccess"
            select="normalize-space((doc:metadata/doc:element[@name='others']/doc:field[@name='anonymousAccess'])[1])"/>
        <xsl:variable name="licenseUriRaw"
            select="normalize-space((doc:metadata/doc:element[@name='dc']/doc:element[@name='rights']/doc:element[@name='uri']/doc:element/doc:field[@name='value'])[1])"/>
        <xsl:variable name="ccUri"
            select="normalize-space((doc:metadata/doc:element[@name='others']/doc:element[@name='cc']/doc:field[@name='uri'])[1])"/>
        <xsl:variable name="ccName"
            select="normalize-space((doc:metadata/doc:element[@name='others']/doc:element[@name='cc']/doc:field[@name='name'])[1])"/>
        <xsl:variable name="hasFiles"
            select="boolean(doc:metadata/doc:element[@name='bundles']/doc:element[@name='bundle'][doc:field[@name='name'] = 'ORIGINAL']/doc:element[@name='bitstreams']/doc:element[@name='bitstream'])"/>
        <!-- a licence IRI must be an IRI; free text belongs in the description -->
        <xsl:variable name="licenseIri"
            select="if (matches($licenseUriRaw, '^https?://\S+$')) then $licenseUriRaw
                    else if (matches($ccUri, '^https?://\S+$')) then $ccUri else ''"/>
        <!-- the licence NAME, never the CLARIN access category, which is not a licence -->
        <xsl:variable name="rightsNodes"
            select="doc:metadata/doc:element[@name='dc']/doc:element[@name='rights']/doc:element/doc:field[@name='value']"/>
        <ccmm:terms_of_use>
            <ccmm:access_rights>
                <xsl:choose>
                    <xsl:when test="not($hasFiles) or $anonymousAccess = 'metadata.only'">
                        <!-- nothing to download -->
                        <ccmm:iri>http://purl.org/coar/access_right/c_14cb</ccmm:iri>
                        <ccmm:label xml:lang="en">metadata only access</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$anonymousAccess = 'embargo'">
                        <ccmm:iri>http://purl.org/coar/access_right/c_f1cf</ccmm:iri>
                        <!-- spelling as listed in the CCMM 1.1.0 specification -->
                        <ccmm:label xml:lang="en">embargoes access</ccmm:label>
                    </xsl:when>
                    <xsl:when test="$anonymousAccess = 'open'">
                        <ccmm:iri>http://purl.org/coar/access_right/c_abf2</ccmm:iri>
                        <ccmm:label xml:lang="en">open access</ccmm:label>
                    </xsl:when>
                    <xsl:otherwise>
                        <!-- 'restricted' and a missing value both mean "not freely downloadable";
                             never default to claiming open access -->
                        <ccmm:iri>http://purl.org/coar/access_right/c_16ec</ccmm:iri>
                        <ccmm:label xml:lang="en">restricted access</ccmm:label>
                    </xsl:otherwise>
                </xsl:choose>
            </ccmm:access_rights>
            <!--
                license is mandatory inside terms_of_use, but license_document has no mandatory
                children, so an element carrying only a label is valid and an empty one is valid
                too.  A made-up IRI is not, so a free-text dc.rights.uri falls through to the
                description below instead of being published as an identifier.
            -->
            <ccmm:license>
                <xsl:if test="$licenseIri != ''">
                    <ccmm:iri><xsl:value-of select="cw:iri($licenseIri)"/></ccmm:iri>
                </xsl:if>
                <xsl:choose>
                    <xsl:when test="$rightsNodes">
                        <xsl:for-each select="$rightsNodes[1]">
                            <ccmm:label><xsl:call-template name="XmlLangAttribute"/><xsl:value-of select="normalize-space(.)"/></ccmm:label>
                        </xsl:for-each>
                    </xsl:when>
                    <xsl:when test="$ccName != ''">
                        <ccmm:label xml:lang="en"><xsl:value-of select="$ccName"/></ccmm:label>
                    </xsl:when>
                    <xsl:when test="$licenseIri = ''">
                        <!--
                            license is 1..1 and the record states no rights at all.  An empty
                            element would satisfy the schema and tell a harvester nothing, so the
                            standard statement for exactly this case is published instead.
                        -->
                        <ccmm:iri>http://rightsstatements.org/vocab/UND/1.0/</ccmm:iri>
                        <ccmm:label xml:lang="en">Copyright Undetermined</ccmm:label>
                    </xsl:when>
                </xsl:choose>
            </ccmm:license>
            <!-- the remaining rights wording; the first value is already the licence label -->
            <xsl:for-each select="$rightsNodes[position() &gt; 1]">
                <ccmm:description><xsl:call-template name="XmlLangAttribute"/><xsl:value-of select="."/></ccmm:description>
            </xsl:for-each>
            <xsl:if test="$licenseUriRaw != '' and $licenseIri != $licenseUriRaw">
                <ccmm:description xml:lang="en"><xsl:value-of select="$licenseUriRaw"/></ccmm:description>
            </xsl:if>
            <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#DistributionInfo#LicenseInfo']/doc:element[@name='restrictionsOfUse']/doc:element/doc:field[@name='value']">
                <xsl:if test="normalize-space(.) != ''">
                    <ccmm:description xml:lang="en"><xsl:value-of select="concat('Restrictions of use: ', normalize-space(.))"/></ccmm:description>
                </xsl:if>
            </xsl:for-each>
            <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#DistributionInfo#LicenseInfo']/doc:element[@name='license']/doc:element/doc:field[@name='value']
                                | doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#DistributionInfo#LicenseInfo']/doc:element[@name='distributionAccessMedium']/doc:element/doc:field[@name='value']">
                <xsl:if test="normalize-space(.) != ''">
                    <ccmm:description xml:lang="en"><xsl:value-of select="concat(replace(string(../../@name), '^.*#', ''), ': ', normalize-space(.))"/></ccmm:description>
                </xsl:if>
            </xsl:for-each>
            <xsl:for-each select="$amEthical[. != '']">
                <ccmm:description xml:lang="en"><xsl:value-of select="concat('Ethical reference: ', .)"/></ccmm:description>
            </xsl:for-each>
            <xsl:call-template name="ContactPoints"/>
        </ccmm:terms_of_use>
    </xsl:template>

    <!--
        Whom to ask about the resource.  LINDAT stores the named contact twice: as the packed
        local.contact.person value "Given;Family;email;affiliation" and, for the migrated
        META-SHARE records, as separate ContactInfo fields.  The repository help address is added
        as an organisational contact so a record always names somebody reachable.
    -->
    <xsl:template name="ContactPoints">
        <xsl:variable name="cp" select="tokenize((doc:metadata/doc:element[@name='local']/doc:element[@name='contact']/doc:element[@name='person']/doc:element/doc:field[@name='value'])[1], ';')"/>
        <xsl:variable name="msBase" select="doc:metadata/doc:element[@name='metashare']"/>
        <xsl:variable name="msGiven" select="normalize-space(($msBase/doc:element[@name='ResourceInfo#ContactInfo#PersonInfo']/doc:element[@name='givenName']/doc:element/doc:field[@name='value'])[1])"/>
        <xsl:variable name="msSur"   select="normalize-space(($msBase/doc:element[@name='ResourceInfo#ContactInfo#PersonInfo']/doc:element[@name='surname']/doc:element/doc:field[@name='value'])[1])"/>
        <xsl:variable name="msMail"  select="normalize-space(($msBase/doc:element[@name='ResourceInfo#ContactInfo#PersonInfo#OrganizationInfo#CommunicationInfo']/doc:element[@name='email']/doc:element/doc:field[@name='value'])[1])"/>
        <xsl:variable name="msOrg"   select="normalize-space(($msBase/doc:element[@name='ResourceInfo#ContactInfo#PersonInfo#OrganizationInfo']/doc:element[@name='organizationName']/doc:element/doc:field[@name='value'])[1])"/>
        <!--
            The migrated catalogue holds the contact as free text: "Name", "Name (email)",
            "Name, email", "Name email", and several people joined by ';'.  A ccmm:person is one
            physical person, so the value is split on ';' - but not on a ';' inside a "(...)"
            note, as in "Vzorová (licensing; project history)".  A part that is only
            an address belongs to the person before it ("Novák; jan.novak@example.org").  The
            address and any "(...)" note are taken out of the name.
        -->
        <xsl:variable name="amc" select="normalize-space($amContact[1])"/>
        <xsl:variable name="emailPattern" select="'[^\s(),;]+@[^\s(),;]+'"/>
        <xsl:variable name="amcNotesHidden">
            <xsl:analyze-string select="$amc" regex="\([^)]*\)">
                <xsl:matching-substring><xsl:value-of select="translate(., ';', '&#xE000;')"/></xsl:matching-substring>
                <xsl:non-matching-substring><xsl:value-of select="."/></xsl:non-matching-substring>
            </xsl:analyze-string>
        </xsl:variable>
        <xsl:variable name="amcParts" select="for $p in tokenize(string($amcNotesHidden), ';') return translate($p, '&#xE000;', ';')"/>
        <xsl:variable name="amcNames"
            select="for $p in $amcParts
                    return normalize-space(replace(replace(replace($p, $emailPattern, ''), '\([^)]*\)', ''), '^[\s,]+|[\s,]+$', ''))"/>
        <xsl:variable name="amcMails"
            select="for $p in $amcParts
                    return (if (matches($p, $emailPattern)) then replace($p, concat('^.*?(', $emailPattern, ').*$'), '$1') else '')"/>
        <xsl:variable name="given"  select="normalize-space((($msGiven, $cp[1])[normalize-space(.) != ''], '')[1])"/>
        <xsl:variable name="family" select="normalize-space((($msSur,   $cp[2])[normalize-space(.) != ''], '')[1])"/>
        <xsl:variable name="mail"   select="normalize-space((($msMail,  $cp[3])[normalize-space(.) != ''], '')[1])"/>
        <xsl:variable name="org"    select="normalize-space((($msOrg,   $cp[4])[normalize-space(.) != ''], '')[1])"/>
        <xsl:if test="concat($given, $family) = ''">
            <xsl:for-each select="1 to count($amcParts)">
                <xsl:variable name="i" select="."/>
                <!-- a bare address stands for itself, as before, unless it follows its person -->
                <xsl:if test="$amcNames[$i] != ''
                              or ($amcMails[$i] != '' and not($i gt 1 and $amcNames[$i - 1] != ''))">
                    <xsl:variable name="personMail"
                        select="($amcMails[$i][. != ''], $amcMails[$i + 1][$amcNames[$i + 1] = ''][. != ''], '')[1]"/>
                    <ccmm:contact_point>
                        <ccmm:person>
                            <ccmm:name><xsl:value-of select="($amcNames[$i][. != ''], $amcMails[$i])[1]"/></ccmm:name>
                            <xsl:if test="$personMail != ''">
                                <ccmm:contact_point><ccmm:email><xsl:value-of select="$personMail"/></ccmm:email></ccmm:contact_point>
                            </xsl:if>
                        </ccmm:person>
                    </ccmm:contact_point>
                </xsl:if>
            </xsl:for-each>
        </xsl:if>
        <xsl:if test="concat($given, $family) != ''">
            <ccmm:contact_point>
                <ccmm:person>
                    <ccmm:name><xsl:value-of select="normalize-space(if ($family != '' and $given != '') then concat($family, ', ', $given) else concat($family, $given))"/></ccmm:name>
                    <xsl:if test="$given != ''"><ccmm:given_name><xsl:value-of select="$given"/></ccmm:given_name></xsl:if>
                    <xsl:if test="$family != ''"><ccmm:family_name><xsl:value-of select="$family"/></ccmm:family_name></xsl:if>
                    <!-- the packed field is positional, so a record that omits one part shifts
                         an affiliation into the email slot; only a real address is published -->
                    <xsl:if test="matches($mail, '^[^\s@]+@[^\s@]+\.[^\s@]+$')">
                        <ccmm:contact_point><ccmm:email><xsl:value-of select="$mail"/></ccmm:email></ccmm:contact_point>
                    </xsl:if>
                    <xsl:if test="$org != ''">
                        <ccmm:affiliation><ccmm:name><xsl:value-of select="$org"/></ccmm:name></ccmm:affiliation>
                    </xsl:if>
                </ccmm:person>
            </ccmm:contact_point>
        </xsl:if>
        <!-- the repository's own role address; never a natural person -->
        <xsl:if test="normalize-space((doc:metadata/doc:element[@name='repository']/doc:field[@name='mail'])[1]) != ''">
            <ccmm:contact_point>
                <ccmm:organization>
                    <ccmm:name>
                        <xsl:choose>
                            <xsl:when test="doc:metadata/doc:element[@name='repository']/doc:field[@name='name']">
                                <xsl:value-of select="normalize-space((doc:metadata/doc:element[@name='repository']/doc:field[@name='name'])[1])"/>
                            </xsl:when>
                            <xsl:otherwise><xsl:value-of select="$FALLBACK_REPOSITORY_NAME"/></xsl:otherwise>
                        </xsl:choose>
                    </ccmm:name>
                    <ccmm:contact_point>
                        <ccmm:email><xsl:value-of select="normalize-space((doc:metadata/doc:element[@name='repository']/doc:field[@name='mail'])[1])"/></ccmm:email>
                    </ccmm:contact_point>
                </ccmm:organization>
            </ccmm:contact_point>
        </xsl:if>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- subject (required, unbounded)                                 -->
    <!-- ============================================================ -->
    <xsl:template name="Subjects">
        <xsl:variable name="deduped">
        <!-- dc.subject -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='subject']/doc:element/doc:field[@name='value']">
            <xsl:call-template name="EmitSubject"/>
        </xsl:for-each>
        <!-- dc.subject.* (nested qualifiers) -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='subject']/doc:element/doc:element/doc:field[@name='value']">
            <xsl:call-template name="EmitSubject"/>
        </xsl:for-each>
        <!-- the META-SHARE resource subtype ("wordnet", "mlmodel", ...) is a subject, not a
             resource_type: COAR has no term for any of them -->
        <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#ContentInfo']/doc:element[@name='detailedType']/doc:element/doc:field[@name='value']">
            <xsl:call-template name="EmitSubject"/>
        </xsl:for-each>
        <!-- the META-SHARE modality: text / audio / video / image -->
        <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#ContentInfo']/doc:element[@name='mediaType']/doc:element/doc:field[@name='value']">
            <xsl:call-template name="EmitSubject"/>
        </xsl:for-each>
        <!-- the META-SHARE resource type and the Europeana type, both content descriptors -->
        <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#ContentInfo']/doc:element[@name='resourceType']/doc:element/doc:field[@name='value']
                            | doc:metadata/doc:element[@name='edm']/doc:element[@name='type']/doc:element/doc:field[@name='value']">
            <xsl:call-template name="EmitSubject"/>
        </xsl:for-each>
        <!-- languages named only in prose, which the language register cannot express -->
        <xsl:for-each select="$amLangOther[. != '']">
            <ccmm:subject><ccmm:title xml:lang="en"><xsl:value-of select="."/></ccmm:title></ccmm:subject>
        </xsl:for-each>
        </xsl:variable>
        <xsl:call-template name="CopyDistinct">
            <xsl:with-param name="nodes" select="$deduped/*"/>
        </xsl:call-template>
        <!--
            subject is 1..unbounded and dc.subject is required at submission, so this is only a
            safety net that keeps the record schema-valid.  No FRASCATI FORD field is derived:
            DSpace does not record one, and guessing it from dc.type would publish a discipline
            the depositor never stated.
        -->
        <xsl:if test="not($deduped/*)">
            <ccmm:subject>
                <ccmm:title xml:lang="en">
                    <xsl:value-of select="if (normalize-space(string(fn:getProperty('oai.ccmm.subject.default'))) != '')
                                          then normalize-space(string(fn:getProperty('oai.ccmm.subject.default')))
                                          else $FALLBACK_SUBJECT"/>
                </ccmm:title>
            </ccmm:subject>
        </xsl:if>
    </xsl:template>

    <!--
        One subject from the value in context.  A "::"-separated value is a path through a
        controlled vocabulary: the leaf is the term and the whole path is its classification
        code.  No subject_scheme is emitted for these - subject-scheme/schema.xsd makes its iri
        mandatory and the thesauri these paths come from publish none.
    -->
    <xsl:template name="EmitSubject">
        <xsl:variable name="v" select="normalize-space(.)"/>
        <xsl:if test="$v != ''">
            <ccmm:subject>
                <ccmm:title>
                    <xsl:call-template name="XmlLangAttribute"/>
                    <xsl:value-of select="if (contains($v, '::')) then normalize-space(tokenize($v, '::')[last()]) else $v"/>
                </ccmm:title>
                <xsl:if test="contains($v, '::')">
                    <ccmm:classification_code><xsl:value-of select="$v"/></ccmm:classification_code>
                </xsl:if>
            </ccmm:subject>
        </xsl:if>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- description (optional, unbounded)                             -->
    <!-- ============================================================ -->
    <xsl:template name="Descriptions">
        <xsl:variable name="deduped">
        <!-- dc.description (abstract) -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='description']/doc:element[@name='abstract']/doc:element/doc:field[@name='value']">
            <ccmm:description>
                <ccmm:description_text><xsl:call-template name="XmlLangAttribute"/><xsl:value-of select="."/></ccmm:description_text>
                <ccmm:description_type>
                    <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/DescriptionType/Abstract</ccmm:iri>
                    <ccmm:label xml:lang="en">Abstract</ccmm:label>
                </ccmm:description_type>
            </ccmm:description>
        </xsl:for-each>
        <!--
            dc.description without a qualifier is the free description a depositor writes; DSpace
            does not say what kind it is, so it is typed Other rather than asserted to be an
            abstract, which is what dc.description.abstract above is.
        -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='description']/doc:element/doc:field[@name='value']">
            <ccmm:description>
                <ccmm:description_text><xsl:call-template name="XmlLangAttribute"/><xsl:value-of select="."/></ccmm:description_text>
                <ccmm:description_type>
                    <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/DescriptionType/Other</ccmm:iri>
                    <ccmm:label xml:lang="en">Other</ccmm:label>
                </ccmm:description_type>
            </ccmm:description>
        </xsl:for-each>
        <!-- dc.format is the media type of a record that has no files to hang a distribution on -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='format']/doc:element/doc:field[@name='value']">
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:description>
                    <ccmm:description_text xml:lang="en"><xsl:value-of select="concat('Format: ', normalize-space(.))"/></ccmm:description_text>
                    <ccmm:description_type>
                        <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/DescriptionType/TechnicalInfo</ccmm:iri>
                        <ccmm:label xml:lang="en">Technical Info</ccmm:label>
                    </ccmm:description_type>
                </ccmm:description>
            </xsl:if>
        </xsl:for-each>
        <!-- dc.description.sponsorship is prose about who paid, not a structured grant -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='description']/doc:element[@name='sponsorship']/doc:element/doc:field[@name='value']">
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:description>
                    <ccmm:description_text><xsl:call-template name="XmlLangAttribute"/><xsl:value-of select="normalize-space(.)"/></ccmm:description_text>
                    <ccmm:description_type>
                        <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/DescriptionType/Other</ccmm:iri>
                        <ccmm:label xml:lang="en">Other</ccmm:label>
                    </ccmm:description_type>
                </ccmm:description>
            </xsl:if>
        </xsl:for-each>
        <!-- the META-SHARE free description -->
        <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#ContentInfo']/doc:element[@name='description']/doc:element/doc:field[@name='value']">
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:description>
                    <ccmm:description_text><xsl:call-template name="XmlLangAttribute"/><xsl:value-of select="normalize-space(.)"/></ccmm:description_text>
                    <ccmm:description_type>
                        <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/DescriptionType/Other</ccmm:iri>
                        <ccmm:label xml:lang="en">Other</ccmm:label>
                    </ccmm:description_type>
                </ccmm:description>
            </xsl:if>
        </xsl:for-each>
        <!--
            local.refbox.format is not published: it is the format string of the UI's citation box
            ("{title}, {authors}, {year}, ...", see local-types.xml), placeholders and all, and the
            values it would fill in are already elements of their own.
        -->
        <!-- META-SHARE technical statements: extent, language coding, language dependence -->
        <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#TextInfo#SizeInfo']/doc:element[@name='size']/doc:element/doc:field[@name='value']">
            <xsl:variable name="unit" select="normalize-space((/doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#TextInfo#SizeInfo']/doc:element[@name='sizeUnit']/doc:element/doc:field[@name='value'])[1])"/>
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:description>
                    <ccmm:description_text xml:lang="en"><xsl:value-of select="normalize-space(concat('Size: ', ., ' ', $unit))"/></ccmm:description_text>
                    <ccmm:description_type>
                        <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/DescriptionType/TechnicalInfo</ccmm:iri>
                        <ccmm:label xml:lang="en">Technical Info</ccmm:label>
                    </ccmm:description_type>
                </ccmm:description>
            </xsl:if>
        </xsl:for-each>
        <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#TextInfo#LanguageInfo']/doc:element[@name='languageCoding']/doc:element/doc:field[@name='value']
                            | doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#ResourceComponentType#ToolServiceInfo']/doc:element[@name='languageDependent']/doc:element/doc:field[@name='value']">
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:description>
                    <ccmm:description_text xml:lang="en"><xsl:value-of select="concat(replace(string(../../@name), '^.*#', ''), ': ', normalize-space(.))"/></ccmm:description_text>
                    <ccmm:description_type>
                        <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/DescriptionType/TechnicalInfo</ccmm:iri>
                        <ccmm:label xml:lang="en">Technical Info</ccmm:label>
                    </ccmm:description_type>
                </ccmm:description>
            </xsl:if>
        </xsl:for-each>
        <!-- local.size.info: "1234;tokens" pairs describing the extent of the resource -->
        <xsl:for-each select="doc:metadata/doc:element[@name='local']/doc:element[@name='size']/doc:element[@name='info']/doc:element/doc:field[@name='value']">
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:description>
                    <ccmm:description_text xml:lang="en"><xsl:value-of select="concat('Size: ', normalize-space(replace(., ';', ' ')))"/></ccmm:description_text>
                    <ccmm:description_type>
                        <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/DescriptionType/TechnicalInfo</ccmm:iri>
                        <ccmm:label xml:lang="en">Technical Info</ccmm:label>
                    </ccmm:description_type>
                </ccmm:description>
            </xsl:if>
        </xsl:for-each>
        </xsl:variable>
        <xsl:call-template name="CopyDistinct">
            <xsl:with-param name="nodes" select="$deduped/*"/>
        </xsl:call-template>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- location (optional, unbounded)                                -->
    <!-- ============================================================ -->
    <!--
        dc.coverage.placeName is the place the resource is about, which is what the
        LocationRelation register calls "Refers".  relation_type is mandatory on a location, so
        without a defensible term the element could not be emitted at all.
    -->
    <xsl:template name="Locations">
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='coverage']/doc:element[@name='placeName']/doc:element/doc:field[@name='value']">
            <!-- DSpace packs repeated values as "A||B"; that is two places, not one name -->
            <xsl:for-each select="tokenize(normalize-space(.), '\|\|')">
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:location>
                    <ccmm:name><xsl:value-of select="normalize-space(.)"/></ccmm:name>
                    <ccmm:relation_type>
                        <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/LocationRelation/Refers</ccmm:iri>
                        <ccmm:label xml:lang="en">Refers to the location</ccmm:label>
                    </ccmm:relation_type>
                </ccmm:location>
            </xsl:if>
            </xsl:for-each>
        </xsl:for-each>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- funding_reference (optional, unbounded)                       -->
    <!-- ============================================================ -->
    <xsl:template name="FundingReferences">
        <!-- sponsors that yield a grant: something in code, funder or project -->
        <xsl:variable name="sponsorValues"
            select="doc:metadata/doc:element[@name='local']/doc:element[@name='sponsor']/doc:element/doc:field[@name='value']
                    [some $p in tokenize(normalize-space(.), ';')[position() = (2, 3, 4)] satisfies normalize-space($p) != '']"/>
        <!--
            The grant numbers already published from the info:eu-repo URIs.  local.sponsor spells
            the same grant differently - "900001" there, "FP7-TEST-0000-0-900001" here - so the
            guard below has to compare both ways round, not just look for one inside the other.
        -->
        <xsl:variable name="uriGrantCodes"
            select="for $v in /doc:metadata/doc:element[@name='dc']/doc:element[@name='relation']/doc:element/doc:field[@name='value']
                               [starts-with(normalize-space(.), 'info:eu-repo/grantAgreement/')]
                    return normalize-space(tokenize(normalize-space($v), '/')[5])"/>
        <xsl:variable name="deduped">
        <!--
            OpenAIRE grant agreements: info:eu-repo/grantAgreement/FUNDER/PROGRAMME/AWARD/...
            The guard names the whole prefix - any other info: URI (info:eu-repo/semantics/...,
            for instance) is not a grant and must not invent a funder.
        -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='relation']/doc:element/doc:field[@name='value']">
            <xsl:variable name="parts" select="tokenize(normalize-space(.), '/')"/>
            <xsl:if test="starts-with(normalize-space(.), 'info:eu-repo/grantAgreement/')
                      and count($parts) &gt;= 5
                      and normalize-space($parts[3]) != '' and normalize-space($parts[5]) != ''">
                <!-- local.sponsor is "org;projectCode;funderName;projectName;infoUri" -->
                <xsl:variable name="sponsor"
                    select="(/doc:metadata/doc:element[@name='local']/doc:element[@name='sponsor']/doc:element/doc:field[@name='value'][contains(., $parts[5])])[1]"/>
                <xsl:variable name="sponsorParts" select="tokenize(string($sponsor), ';')"/>
                <xsl:variable name="sponsorFunder" select="normalize-space($sponsorParts[3])"/>
                <xsl:variable name="sponsorProject" select="normalize-space($sponsorParts[4])"/>
                <ccmm:funding_reference>
                    <ccmm:local_identifier><xsl:value-of select="replace(replace($parts[5], '%2F', '/'), '%2f', '/')"/></ccmm:local_identifier>
                    <xsl:if test="$sponsorProject != ''">
                        <ccmm:award_title><xsl:value-of select="$sponsorProject"/></ccmm:award_title>
                    </xsl:if>
                    <!--
                        No funding_program: CCMM wants a reference (xsd:anyURI) to the programme,
                        and the PROGRAMME segment is only a code (FP7, H2020) - not a reference.
                    -->
                    <ccmm:funder>
                        <ccmm:organization>
                            <ccmm:name>
                                <xsl:choose>
                                    <xsl:when test="$sponsorFunder != ''"><xsl:value-of select="$sponsorFunder"/></xsl:when>
                                    <xsl:when test="$parts[3] = 'EC'">European Commission</xsl:when>
                                    <xsl:when test="$parts[3] = 'MSM'">Ministry of Education, Youth and Sports of the Czech Republic</xsl:when>
                                    <xsl:when test="$parts[3] = 'GACR'">Czech Science Foundation</xsl:when>
                                    <xsl:when test="$parts[3] = 'TACR'">Technology Agency of the Czech Republic</xsl:when>
                                    <xsl:otherwise><xsl:value-of select="$parts[3]"/></xsl:otherwise>
                                </xsl:choose>
                            </ccmm:name>
                        </ccmm:organization>
                    </ccmm:funder>
                </ccmm:funding_reference>
            </xsl:if>
        </xsl:for-each>
        <!--
            The migrated META-SHARE project tree has a project name but no grant number, prefixes
            every value of a repeated field with its position ("#1-Example Project Gamma" - storage, not
            part of the name), and has no funder at all: fundingType is a category (euFunds, nationalFunds, ownFunds), not who
            paid.  local.sponsor holds the same grants properly, with numbers and funder names that
            cannot be matched to these titles reliably, so the tree is used only for a record
            without local.sponsor; the funder, required by the schema, is then stated as unknown.
        -->
        <xsl:if test="not($sponsorValues)">
            <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#ResourceCreationInfo#FundingInfo#ProjectInfo']/doc:element[@name='projectName']/doc:element/doc:field[@name='value']">
                <xsl:variable name="project" select="normalize-space(replace(normalize-space(.), '^#[0-9]+-', ''))"/>
                <xsl:if test="$project != ''">
                    <ccmm:funding_reference>
                        <ccmm:award_title><xsl:value-of select="$project"/></ccmm:award_title>
                        <ccmm:funder>
                            <ccmm:organization>
                                <ccmm:name>:unkn</ccmm:name>
                            </ccmm:organization>
                        </ccmm:funder>
                    </ccmm:funding_reference>
                </xsl:if>
            </xsl:for-each>
        </xsl:if>
        <!--
            local.sponsor carries the same grants in structured form, including the ones that
            never got an info: URI.  Emitted only when the project code is not already above.
        -->
        <xsl:for-each select="doc:metadata/doc:element[@name='local']/doc:element[@name='sponsor']/doc:element/doc:field[@name='value']">
            <!--
                "type;code;funder;project;uri".  An empty part is left out on its own - a grant
                without a number is still a grant of its funder - and a value with nothing in code,
                funder or project is none.  The type (euFunds, nationalFunds, ownFunds, Other) is a
                category, never the funder.
            -->
            <xsl:variable name="sp" select="tokenize(normalize-space(.), ';')"/>
            <xsl:variable name="code" select="normalize-space($sp[2])"/>
            <xsl:variable name="funderName" select="normalize-space($sp[3])"/>
            <xsl:variable name="project" select="normalize-space($sp[4])"/>
            <xsl:if test="concat($code, $funderName, $project) != ''
                      and not($code != '' and (some $g in $uriGrantCodes satisfies
                              (string-length($g) &gt;= 4 and (contains($code, $g) or contains($g, $code)))))">
                <ccmm:funding_reference>
                    <xsl:if test="$code != ''">
                        <ccmm:local_identifier><xsl:value-of select="$code"/></ccmm:local_identifier>
                    </xsl:if>
                    <xsl:if test="$project != ''">
                        <ccmm:award_title><xsl:value-of select="$project"/></ccmm:award_title>
                    </xsl:if>
                    <ccmm:funder>
                        <ccmm:organization>
                            <ccmm:name><xsl:value-of select="if ($funderName != '') then $funderName else ':unkn'"/></ccmm:name>
                        </ccmm:organization>
                    </ccmm:funder>
                </ccmm:funding_reference>
            </xsl:if>
        </xsl:for-each>
        </xsl:variable>
        <xsl:call-template name="CopyDistinct">
            <xsl:with-param name="nodes" select="$deduped/*"/>
        </xsl:call-template>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- related_resource (optional, unbounded)                        -->
    <!-- ============================================================ -->
    <!-- (funding continues below in FundingReferences) -->
    <xsl:template name="RelatedResources">
        <!-- the URLs the record's distributions are reached at -->
        <xsl:param name="distributionIris" as="xs:string*" select="()"/>
        <xsl:variable name="deduped">
        <!-- every dc.relation qualifier that names another resource -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='relation']/doc:element[@name = ('uri', 'ispartof', 'haspart', 'replaces', 'isreplacedby', 'isreferencedby', 'references', 'isbasedon', 'isversionof', 'hasversion', 'requires', 'isrequiredby', 'isformatof', 'hasformat')]/doc:element/doc:field[@name='value']">
            <xsl:variable name="rel" select="../../@name"/>
            <xsl:call-template name="EmitRelatedResource">
                <xsl:with-param name="relType" select="$rel"/>
            </xsl:call-template>
        </xsl:for-each>
        <!-- dc.source.uri is the resource's own project or landing page -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='source']/doc:element[@name='uri']/doc:element/doc:field[@name='value']">
            <xsl:call-template name="EmitRelatedResource">
                <xsl:with-param name="relType" select="'references'"/>
                <xsl:with-param name="assumed" select="true()"/>
            </xsl:call-template>
        </xsl:for-each>
        <!-- local.demo.uri is a live demonstration of the resource -->
        <xsl:for-each select="doc:metadata/doc:element[@name='local']/doc:element[@name='demo']/doc:element[@name='uri']/doc:element/doc:field[@name='value']">
            <xsl:call-template name="EmitRelatedResource">
                <xsl:with-param name="relType" select="'references'"/>
                <xsl:with-param name="assumed" select="true()"/>
            </xsl:call-template>
        </xsl:for-each>
        <!-- dc.identifier.citation cites the paper that describes the dataset -->
        <xsl:for-each select="doc:metadata/doc:element[@name='dc']/doc:element[@name='identifier']/doc:element[@name='citation']/doc:element/doc:field[@name='value']">
            <xsl:call-template name="EmitRelatedResource">
                <xsl:with-param name="relType" select="'isreferencedby'"/>
            </xsl:call-template>
        </xsl:for-each>
        <!-- publications describing the resource, from the migrated catalogue -->
        <xsl:for-each select="$amPublications[. != '']">
            <ccmm:related_resource>
                <xsl:choose>
                    <!-- the whole line has to be one URL; a URL followed by prose is a title -->
                    <xsl:when test="matches(., '^https?://\S+$')">
                        <ccmm:iri><xsl:value-of select="cw:iri(.)"/></ccmm:iri>
                        <ccmm:resource_url><xsl:value-of select="cw:iri(.)"/></ccmm:resource_url>
                    </xsl:when>
                    <xsl:otherwise><ccmm:title><xsl:value-of select="."/></ccmm:title></xsl:otherwise>
                </xsl:choose>
                <ccmm:resource_relation_type>
                    <ccmm:iri>https://vocabs.ccmm.cz/registry/codelist/RelationType/IsReferencedBy</ccmm:iri>
                    <ccmm:label xml:lang="en">is referenced by</ccmm:label>
                </ccmm:resource_relation_type>
            </ccmm:related_resource>
        </xsl:for-each>
        <!-- the DSpace collection this item belongs to -->
        <xsl:for-each select="doc:metadata/doc:element[@name='others']/doc:field[@name='owningCollection']">
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:related_resource>
                    <ccmm:title><xsl:value-of select="normalize-space(.)"/></ccmm:title>
                    <xsl:call-template name="RelationType">
                        <xsl:with-param name="relType" select="'ispartof'"/>
                    </xsl:call-template>
                </ccmm:related_resource>
            </xsl:if>
        </xsl:for-each>
        </xsl:variable>
        <!--
            A record may name one IRI with a stated relation and again untyped (dc.relation.uri)
            or as an assumed reference (dc.source.uri).  The repetition gives way to the stated
            relation, an untyped one to an assumed one.  Every relation the record states is
            published, also two different ones to one resource - choosing between them would be
            correcting the record.  The same relation stated twice (dc.relation and the migrated
            catalogue's publications) is one element, the first.  Resources known only by a title
            have no identity to compare and all stay.  A URL that is already one of the record's
            distributions (a featured service also given as the demo, a web service also given as
            the source) is not repeated as a related resource: CCMM says distributions SHALL NOT be
            modeled as related resources.
        -->
        <xsl:variable name="perIri" as="element()*">
            <xsl:for-each-group select="$deduped/*[not(ccmm:iri = $distributionIris)]" group-by="if (ccmm:iri) then string(ccmm:iri) else generate-id()">
                <xsl:variable name="best" select="min(for $r in current-group() return cw:relationRank($r))"/>
                <xsl:for-each-group select="current-group()[cw:relationRank(.) = $best]"
                                    group-by="string(ccmm:resource_relation_type/ccmm:iri)">
                    <xsl:for-each select="current-group()[1]">
                        <!-- without the cw:assumed mark and its namespace, on every level -->
                        <xsl:copy copy-namespaces="no"><xsl:copy-of select="node()" copy-namespaces="no"/></xsl:copy>
                    </xsl:for-each>
                </xsl:for-each-group>
            </xsl:for-each-group>
        </xsl:variable>
        <xsl:call-template name="CopyDistinct">
            <xsl:with-param name="nodes" select="$perIri"/>
        </xsl:call-template>
    </xsl:template>

    <!--
        1 for a relation the record states with a type, 2 for one the crosswalk only assumes
        (cw:assumed, from dc.source.uri or local.demo.uri), 3 for an untyped one.
    -->
    <xsl:function name="cw:relationRank" as="xs:integer">
        <xsl:param name="resource" as="element()"/>
        <xsl:sequence select="if ($resource/@cw:assumed) then 2
                              else if (normalize-space($resource/ccmm:resource_relation_type/ccmm:iri) = '') then 3
                              else 1"/>
    </xsl:function>

    <!--
        One related_resource from the value in context.  A value that is a URL identifies the
        resource and is published as its IRI and resource_url; anything else is a title, which is
        all a bare citation or collection name can honestly be.
    -->
    <xsl:template name="EmitRelatedResource">
        <xsl:param name="relType"/>
        <!-- true for a relation the source does not state but the crosswalk assumes; it gives way
             to any stated one for the same resource (see RelatedResources) -->
        <xsl:param name="assumed" select="false()"/>
        <xsl:variable name="v" select="normalize-space(.)"/>
        <xsl:if test="$v != ''">
            <ccmm:related_resource>
                <xsl:if test="$assumed"><xsl:attribute name="cw:assumed">true</xsl:attribute></xsl:if>
                <xsl:choose>
                    <!-- the WHOLE value has to be one URI: "http://a http://b" and a URL
                         followed by prose are titles, not identifiers -->
                    <xsl:when test="matches($v, '^https?://\S+$')">
                        <ccmm:iri><xsl:value-of select="cw:iri($v)"/></ccmm:iri>
                        <!-- the same notation/iri split the dataset's own identifiers use -->
                        <xsl:call-template name="EmitIdentifier"/>
                        <ccmm:resource_url><xsl:value-of select="cw:iri($v)"/></ccmm:resource_url>
                    </xsl:when>
                    <xsl:otherwise>
                        <ccmm:title><xsl:value-of select="$v"/></ccmm:title>
                    </xsl:otherwise>
                </xsl:choose>
                <xsl:call-template name="RelationType">
                    <xsl:with-param name="relType" select="$relType"/>
                </xsl:call-template>
            </ccmm:related_resource>
        </xsl:if>
    </xsl:template>

    <!--
        The DCMI relation qualifier as a CCMM RelationType term.  The register has no "Replaces"
        or "IsReplacedBy"; the concepts for superseding are Obsoletes and IsObsoletedBy.  Both the
        term and its label are taken from the register itself.
    -->
    <xsl:template name="RelationType">
        <xsl:param name="relType"/>
        <xsl:variable name="term">
            <xsl:choose>
                <xsl:when test="$relType = 'ispartof'">IsPartOf</xsl:when>
                <xsl:when test="$relType = 'haspart'">HasPart</xsl:when>
                <xsl:when test="$relType = 'replaces'">Obsoletes</xsl:when>
                <xsl:when test="$relType = 'isreplacedby'">IsObsoletedBy</xsl:when>
                <xsl:when test="$relType = 'isreferencedby'">IsReferencedBy</xsl:when>
                <xsl:when test="$relType = 'references'">References</xsl:when>
                <xsl:when test="$relType = 'isversionof'">IsVersionOf</xsl:when>
                <xsl:when test="$relType = 'hasversion'">HasVersion</xsl:when>
                <xsl:when test="$relType = 'requires'">Requires</xsl:when>
                <xsl:when test="$relType = 'isrequiredby'">IsRequiredBy</xsl:when>
                <xsl:when test="$relType = 'isbasedon'">IsDerivedFrom</xsl:when>
                <xsl:when test="$relType = 'isformatof'">IsVariantFormOf</xsl:when>
                <xsl:when test="$relType = 'hasformat'">IsOriginalFormOf</xsl:when>
                <!-- dc.relation.uri asserts a relation without saying which, and the register has
                     no term for that; resource_relation_type is optional, so it is left out -->
                <xsl:otherwise/>
            </xsl:choose>
        </xsl:variable>
        <xsl:variable name="termLabel">
            <xsl:choose>
                <xsl:when test="$term = 'IsPartOf'">is part of</xsl:when>
                <xsl:when test="$term = 'HasPart'">has part</xsl:when>
                <xsl:when test="$term = 'Obsoletes'">obsoletes</xsl:when>
                <xsl:when test="$term = 'IsObsoletedBy'">is obsoleted by</xsl:when>
                <xsl:when test="$term = 'IsReferencedBy'">is referenced by</xsl:when>
                <xsl:when test="$term = 'References'">references</xsl:when>
                <xsl:when test="$term = 'IsVersionOf'">is version of</xsl:when>
                <xsl:when test="$term = 'HasVersion'">has version</xsl:when>
                <xsl:when test="$term = 'Requires'">requires</xsl:when>
                <xsl:when test="$term = 'IsRequiredBy'">is required by</xsl:when>
                <xsl:when test="$term = 'IsDerivedFrom'">is derived from</xsl:when>
                <xsl:when test="$term = 'IsVariantFormOf'">is variant form of</xsl:when>
                <xsl:when test="$term = 'IsOriginalFormOf'">is original form of</xsl:when>
                <xsl:otherwise/>
            </xsl:choose>
        </xsl:variable>
        <xsl:if test="$term != ''">
            <ccmm:resource_relation_type>
                <ccmm:iri><xsl:value-of select="concat('https://vocabs.ccmm.cz/registry/codelist/RelationType/', $term)"/></ccmm:iri>
                <ccmm:label xml:lang="en"><xsl:value-of select="$termLabel"/></ccmm:label>
            </ccmm:resource_relation_type>
        </xsl:if>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- distribution (optional, unbounded)                            -->
    <!-- ============================================================ -->
    <!--
        What a harvester can actually fetch.  Every bitstream of the ORIGINAL bundle becomes a
        downloadable file; XOAI supplies all four things the schema demands of one - a title, an
        access URL, a format and a byte size - so a bitstream missing any of them is skipped
        rather than padded with an invented value.  The LICENSE, THUMBNAIL and TEXT bundles are
        DSpace's own machinery and are not distributions of the resource.
    -->
    <xsl:template name="Distributions">
        <xsl:for-each select="doc:metadata/doc:element[@name='bundles']/doc:element[@name='bundle'][doc:field[@name='name'] = 'ORIGINAL']/doc:element[@name='bitstreams']/doc:element[@name='bitstream']">
            <xsl:variable name="url" select="normalize-space((doc:field[@name='url'])[1])"/>
            <xsl:variable name="size" select="normalize-space((doc:field[@name='size'])[1])"/>
            <xsl:variable name="fmt" select="normalize-space(tokenize(normalize-space((doc:field[@name='format'])[1]), ';')[1])"/>
            <xsl:variable name="nm" select="normalize-space((doc:field[@name='name'], doc:field[@name='originalName'])[normalize-space(.) != ''][1])"/>
            <xsl:variable name="uuid" select="normalize-space((doc:field[@name='id'])[1])"/>
            <!--
                The by-handle endpoint matches on the bitstream's own name, so the name field is
                what goes in the path - not originalName, which the endpoint never compares.  With
                no handle or no name there is nothing to address the file by, and the url XOAI
                supplied is kept instead.
            -->
            <xsl:variable name="fileName" select="normalize-space((doc:field[@name='name'])[1])"/>
            <xsl:variable name="downloadUrl"
                select="cw:iri(if ($downloadBase != '' and $itemHandle != '' and $fileName != '')
                               then concat($downloadBase, $itemHandle, '/', encode-for-uri($fileName))
                               else $url)"/>
            <xsl:variable name="sum" select="normalize-space((doc:field[@name='checksum'])[1])"/>
            <xsl:variable name="alg" select="normalize-space((doc:field[@name='checksumAlgorithm'])[1])"/>
            <!--
                The published URL is what has to be resolvable, and that is download_url.  Gating
                on the XOAI url instead would drop the file whenever XOAI could not build one -
                ItemUtils emits a bare filename when the item has no handle - even though the
                endpoint URL was derivable from the Handle and the name.
            -->
            <xsl:if test="matches($downloadUrl, '^https?://\S+$') and matches($size, '^[0-9]+$') and matches($fmt, '^[A-Za-z0-9!#$&amp;^_.+-]+/[A-Za-z0-9!#$&amp;^_.+-]+$')">
                <ccmm:distribution>
                    <ccmm:distribution_downloadable_file>
                        <!-- a file name, never a URL: the last path segment is the name XOAI encoded -->
                        <ccmm:title><xsl:value-of select="if ($nm != '') then $nm
                                                          else if ($url != '') then tokenize($url, '/')[last()]
                                                          else concat('bitstream-', $uuid)"/></ccmm:title>
                        <!--
                            access_url is the page that says how to get the resource, so it is the
                            item's landing page.  A record with no Handle at all has no page to
                            point at, and access_url is mandatory, so it falls back to the same
                            route download_url uses, which is at least resolvable.
                        -->
                        <ccmm:access_url>
                            <ccmm:iri><xsl:value-of select="if ($landingPage != '') then $landingPage else $downloadUrl"/></ccmm:iri>
                        </ccmm:access_url>
                        <ccmm:download_url><ccmm:iri><xsl:value-of select="$downloadUrl"/></ccmm:iri></ccmm:download_url>
                        <xsl:variable name="eu" select="$euFileTypes/t[@m = $fmt]"/>
                        <ccmm:format>
                            <xsl:choose>
                                <xsl:when test="$eu">
                                    <ccmm:iri><xsl:value-of select="concat($EU_FILETYPE_IRI, $eu[1]/@c)"/></ccmm:iri>
                                    <ccmm:label xml:lang="en"><xsl:value-of select="$eu[1]/@l"/></ccmm:label>
                                </xsl:when>
                                <xsl:otherwise>
                                    <!-- no EU concept for this media type; a true IANA value beats an invented one -->
                                    <ccmm:iri><xsl:value-of select="concat('https://www.iana.org/assignments/media-types/', $fmt)"/></ccmm:iri>
                                    <ccmm:label xml:lang="en"><xsl:value-of select="$fmt"/></ccmm:label>
                                </xsl:otherwise>
                            </xsl:choose>
                        </ccmm:format>
                        <!--
                            media_type's scope note applies "when the media type is defined in
                            IANA".  RFC 6838 reserves the "x-" subtype prefix for types that are
                            NOT registered, so those get no media_type and the mandatory EU
                            format above carries the file type instead - which is exactly the
                            "otherwise Format MAY be used" branch of the same scope note.
                        -->
                        <xsl:if test="not(matches($fmt, '/x-'))">
                            <ccmm:media_type>
                                <ccmm:iri><xsl:value-of select="concat('https://www.iana.org/assignments/media-types/', $fmt)"/></ccmm:iri>
                                <ccmm:label xml:lang="en"><xsl:value-of select="$fmt"/></ccmm:label>
                            </ccmm:media_type>
                        </xsl:if>
                        <ccmm:byte_size><xsl:value-of select="$size"/></ccmm:byte_size>
                        <xsl:if test="matches($sum, '^[0-9a-fA-F]+$') and string-length($sum) mod 2 = 0 and $alg != ''">
                            <ccmm:checksum>
                                <ccmm:checksum_value><xsl:value-of select="$sum"/></ccmm:checksum_value>
                                <ccmm:algorithm>
                                    <ccmm:iri><xsl:value-of select="concat('http://spdx.org/rdf/terms#checksumAlgorithm_', lower-case(replace($alg, '[^A-Za-z0-9]', '')))"/></ccmm:iri>
                                    <ccmm:label xml:lang="en"><xsl:value-of select="$alg"/></ccmm:label>
                                </ccmm:algorithm>
                            </ccmm:checksum>
                        </xsl:if>
                    </ccmm:distribution_downloadable_file>
                </ccmm:distribution>
            </xsl:if>
        </xsl:for-each>
        <!-- live services over the resource: featured services and the tool web service -->
        <xsl:for-each select="doc:metadata/doc:element[@name='local']/doc:element[@name='featuredService']/doc:element/doc:element/doc:field[@name='value']">
            <xsl:variable name="v" select="normalize-space(.)"/>
            <xsl:variable name="lab" select="if (contains($v, '|')) then normalize-space(substring-before($v, '|')) else ''"/>
            <xsl:variable name="url" select="normalize-space(if (contains($v, '|')) then substring-after($v, '|') else $v)"/>
            <xsl:if test="matches($url, '^https?://\S+$')">
                <xsl:call-template name="DataServiceDistribution">
                    <xsl:with-param name="title" select="if ($lab != '') then concat(../../@name, ': ', $lab) else string(../../@name)"/>
                    <xsl:with-param name="url" select="$url"/>
                </xsl:call-template>
            </xsl:if>
        </xsl:for-each>
        <xsl:for-each select="$amWebservice[matches(., '^https?://\S+$')]">
            <xsl:call-template name="DataServiceDistribution">
                <xsl:with-param name="title" select="'Web service'"/>
                <xsl:with-param name="url" select="."/>
            </xsl:call-template>
        </xsl:for-each>
    </xsl:template>

    <xsl:template name="DataServiceDistribution">
        <xsl:param name="title"/>
        <xsl:param name="url"/>
        <ccmm:distribution>
            <ccmm:distribution_data_service>
                <ccmm:title><xsl:value-of select="$title"/></ccmm:title>
                <ccmm:access_service>
                    <ccmm:iri><xsl:value-of select="cw:iri($url)"/></ccmm:iri>
                    <ccmm:label xml:lang="en"><xsl:value-of select="$title"/></ccmm:label>
                    <ccmm:endpoint_url>
                        <ccmm:iri><xsl:value-of select="cw:iri($url)"/></ccmm:iri>
                        <ccmm:resource_url><xsl:value-of select="cw:iri($url)"/></ccmm:resource_url>
                    </ccmm:endpoint_url>
                </ccmm:access_service>
                <xsl:for-each select="$amDoclink[matches(., '^https?://\S+$')]">
                    <ccmm:documentation>
                        <ccmm:iri><xsl:value-of select="cw:iri(.)"/></ccmm:iri>
                        <ccmm:label xml:lang="en">Documentation</ccmm:label>
                    </ccmm:documentation>
                </xsl:for-each>
            </ccmm:distribution_data_service>
        </ccmm:distribution>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- validation_result and provenance                              -->
    <!-- ============================================================ -->
    <xsl:template name="ValidationResults">
        <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#ValidationInfo']/doc:element[@name='validated']/doc:element/doc:field[@name='value']">
            <ccmm:validation_result>
                <ccmm:label xml:lang="en">
                    <xsl:value-of select="if (lower-case(normalize-space(.)) = 'true')
                                          then 'Validated by the repository'
                                          else 'Not validated by the repository'"/>
                </ccmm:label>
            </ccmm:validation_result>
        </xsl:for-each>
    </xsl:template>

    <xsl:template name="Provenances">
        <xsl:for-each select="doc:metadata/doc:element[@name='local']/doc:element[@name='dataProvider']/doc:element/doc:field[@name='value']">
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:provenance>
                    <ccmm:label xml:lang="en"><xsl:value-of select="concat('Data provider: ', normalize-space(.))"/></ccmm:label>
                </ccmm:provenance>
            </xsl:if>
        </xsl:for-each>
        <xsl:for-each select="doc:metadata/doc:element[@name='metashare']/doc:element[@name='ResourceInfo#DistributionInfo']/doc:element[@name='availability']/doc:element/doc:field[@name='value']">
            <xsl:if test="normalize-space(.) != ''">
                <ccmm:provenance>
                    <ccmm:label xml:lang="en"><xsl:value-of select="concat('META-SHARE availability: ', normalize-space(.))"/></ccmm:label>
                </ccmm:provenance>
            </xsl:if>
        </xsl:for-each>
    </xsl:template>

    <!--
        Deliberately not mapped, because none of it describes the resource:
          local.branding, local.hidden, local.hasCMDI, local.dataProvider flags and the
          local.files.* counters are repository bookkeeping; license@bin is the deposit licence
          blob; bundles other than ORIGINAL (LICENSE, THUMBNAIL, TEXT) are DSpace's own machinery.
        Carried in the source but with nowhere to go in CCMM 1.1.0:
          the per-file description on a bitstream - distribution_downloadable_file has no
          description element - and the deprecated language variants of a subject path.
    -->

    <!--
        Copies each distinct child once.  Two siblings carrying the same value are one value in
        the CCMM model and two in XML, which a consumer displays twice; the key below - element
        names, text and xml:lang - is what makes them identical.

        Each text node is keyed separately and normalised: the source repeats descriptions that
        differ only in a stray carriage return, and concatenating the text without a separator
        would also let <title>AB</title><iri>C</iri> collide with <title>A</title><iri>BC</iri>.
        Only the key is normalised - the text is copied through untouched, so paragraph breaks
        in an abstract survive.

        DSpace often stores one value twice, once without a language ("none", xml:lang="und") and
        once with one ("en_US").  Such a pair is one value too: when a copy whose every xml:lang
        is known exists, the copies with an unknown language are dropped.  Copies in different
        known languages stay apart.
    -->
    <xsl:template name="CopyDistinct">
        <xsl:param name="nodes"/>
        <xsl:for-each-group select="$nodes"
            group-by="concat(string-join(descendant-or-self::*/name(), '|'), '#',
                             string-join(for $t in descendant::text() return normalize-space($t), '|'))">
            <xsl:variable name="withKnownLanguage"
                select="current-group()[not(descendant-or-self::*/@xml:lang = ('', 'und'))]"/>
            <xsl:for-each-group select="if (exists($withKnownLanguage)) then $withKnownLanguage else current-group()"
                group-by="string-join(descendant-or-self::*/@xml:lang, '|')">
                <xsl:copy-of select="current-group()[1]"/>
            </xsl:for-each-group>
        </xsl:for-each-group>
    </xsl:template>

    <!-- ============================================================ -->
    <!-- Helper: Format a date string to xs:date or xs:dateTime        -->
    <!-- ============================================================ -->
    <!--
        time_instant is a choice of date_time or date, both strongly typed, so every branch has to
        prove the value casts before emitting it.  A value that cannot be expressed produces
        nothing at all; an empty <date/> would make the record invalid.  Callers of an optional
        time reference filter the value first, the mandatory Issued/Created pair falls back to a
        year that always casts.
    -->
    <xsl:template name="FormatDate">
        <xsl:param name="dateStr"/>
        <xsl:variable name="s" select="normalize-space(string($dateStr))"/>
        <xsl:variable name="y" select="tokenize($s, '[^0-9]+')[matches(., '^[0-9]{4}$')][1]"/>
        <xsl:choose>
            <xsl:when test="$s castable as xs:dateTime">
                <ccmm:date_time><xsl:value-of select="$s"/></ccmm:date_time>
            </xsl:when>
            <xsl:when test="$s castable as xs:date">
                <ccmm:date><xsl:value-of select="$s"/></ccmm:date>
            </xsl:when>
            <xsl:when test="concat($s, '-01') castable as xs:date">
                <ccmm:date><xsl:value-of select="concat($s, '-01')"/></ccmm:date>
            </xsl:when>
            <xsl:when test="concat($s, '-01-01') castable as xs:date">
                <ccmm:date><xsl:value-of select="concat($s, '-01-01')"/></ccmm:date>
            </xsl:when>
            <xsl:when test="$y and concat($y, '-01-01') castable as xs:date">
                <ccmm:date><xsl:value-of select="concat($y, '-01-01')"/></ccmm:date>
            </xsl:when>
        </xsl:choose>
    </xsl:template>

    <!--
        One identifier from the value in context.  CCMM types value as skos:notation - the
        identifier WITHIN its scheme - and offers a separate iri for the resolvable form, which
        is the split CCMM's own samples use.  So a handle URL yields the bare handle plus the
        resolvable URL, a DOI in any of its three written forms yields the bare DOI plus
        https://doi.org/..., and anything else keeps its value, gaining an iri only when the
        whole value really is one URL.
    -->
    <xsl:template name="EmitIdentifier">
        <xsl:variable name="raw" select="normalize-space(.)"/>
        <xsl:variable name="isHandle" select="contains($raw, 'hdl.handle.net') or contains($raw, '/handle/')"/>
        <xsl:variable name="isDoi"
            select="not($isHandle) and (matches($raw, '^https?://(dx\.)?doi\.org/')
                                        or starts-with($raw, 'doi:') or starts-with($raw, '10.'))"/>
        <xsl:variable name="notation">
            <xsl:choose>
                <xsl:when test="$isHandle"><xsl:value-of select="replace($raw, '^.*?(hdl\.handle\.net/|/handle/)', '')"/></xsl:when>
                <xsl:when test="$isDoi"><xsl:value-of select="replace(replace($raw, '^https?://(dx\.)?doi\.org/', ''), '^doi:', '')"/></xsl:when>
                <xsl:otherwise><xsl:value-of select="$raw"/></xsl:otherwise>
            </xsl:choose>
        </xsl:variable>
        <xsl:variable name="iri">
            <xsl:choose>
                <xsl:when test="$isHandle and matches($raw, '^https?://\S+$')"><xsl:value-of select="$raw"/></xsl:when>
                <xsl:when test="$isHandle"><xsl:value-of select="concat('https://hdl.handle.net/', $notation)"/></xsl:when>
                <xsl:when test="$isDoi"><xsl:value-of select="concat('https://doi.org/', $notation)"/></xsl:when>
                <xsl:when test="matches($raw, '^https?://\S+$')"><xsl:value-of select="$raw"/></xsl:when>
                <xsl:otherwise/>
            </xsl:choose>
        </xsl:variable>
        <xsl:if test="$notation != ''">
            <ccmm:identifier>
                <xsl:if test="$iri != ''"><ccmm:iri><xsl:value-of select="cw:iri($iri)"/></ccmm:iri></xsl:if>
                <ccmm:value><xsl:value-of select="$notation"/></ccmm:value>
                <ccmm:scheme><xsl:call-template name="IdentifierScheme"/></ccmm:scheme>
            </ccmm:identifier>
        </xsl:if>
    </xsl:template>

    <!--
        The scheme a value actually belongs to, called with the value as the context node.  The
        DSpace qualifier names the scheme where it can (dc.identifier.issn, .isbn); otherwise the
        value itself does.  A value that is neither is a local identifier of the repository, which
        is what identifier_scheme's mandatory iri then points at.
    -->
    <xsl:template name="IdentifierScheme">
        <xsl:variable name="qualifier" select="lower-case(string(../../@name))"/>
        <xsl:choose>
            <xsl:when test="$qualifier = 'issn'">
                <ccmm:iri>https://portal.issn.org/</ccmm:iri>
                <ccmm:label xml:lang="en">ISSN</ccmm:label>
            </xsl:when>
            <xsl:when test="$qualifier = 'isbn'">
                <ccmm:iri>https://www.isbn-international.org/</ccmm:iri>
                <ccmm:label xml:lang="en">ISBN</ccmm:label>
            </xsl:when>
            <xsl:when test="contains(., 'doi.org/') or starts-with(normalize-space(.), 'doi:') or starts-with(normalize-space(.), '10.')">
                <ccmm:iri>https://doi.org/</ccmm:iri>
                <ccmm:label xml:lang="en">DOI</ccmm:label>
            </xsl:when>
            <xsl:when test="contains(., 'hdl.handle.net') or contains(., '/handle/')">
                <ccmm:iri>https://hdl.handle.net/</ccmm:iri>
                <ccmm:label xml:lang="en">Handle</ccmm:label>
            </xsl:when>
            <xsl:when test="matches(normalize-space(.), '^[a-z][a-z0-9+.-]*:')">
                <ccmm:iri>https://www.iana.org/assignments/uri-schemes</ccmm:iri>
                <ccmm:label xml:lang="en">URI</ccmm:label>
            </xsl:when>
            <xsl:otherwise>
                <ccmm:iri>
                    <xsl:choose>
                        <xsl:when test="/doc:metadata/doc:element[@name='repository']/doc:field[@name='url']">
                            <xsl:value-of select="normalize-space((/doc:metadata/doc:element[@name='repository']/doc:field[@name='url'])[1])"/>
                        </xsl:when>
                        <xsl:otherwise><xsl:value-of select="$FALLBACK_REPOSITORY_URL"/></xsl:otherwise>
                    </xsl:choose>
                </ccmm:iri>
                <ccmm:label xml:lang="en">Local identifier</ccmm:label>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

</xsl:stylesheet>
