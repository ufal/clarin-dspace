<?xml version="1.0" encoding="UTF-8" ?>
<!-- 
    
    
    The contents of this file are subject to the license and copyright
    detailed in the LICENSE and NOTICE files at the root of the source
    tree and available online at
    
    http://www.dspace.org/license/
	Developed by DSpace @ Lyncode <dspace@lyncode.com>
	
	> http://www.openarchives.org/OAI/2.0/oai_dc.xsd
	
 -->
<xsl:stylesheet 
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:doc="http://www.lyncode.com/xoai"
    version="1.0">
    <xsl:output omit-xml-declaration="yes" method="xml" indent="yes" encoding="utf-8"/>
    
    <xsl:template match="/">
    
    <qdc_vut>    
    
         <!-- TITLE -->
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='title']/doc:element">
            <title>
                
                <xsl:choose>
                    
                    <xsl:when test="local-name(*[1])='field'">
                        <xsl:value-of select="./doc:field"/>
                        
                    </xsl:when>
                    <xsl:otherwise>
                        <xsl:value-of select="./doc:element/doc:field"/>        
                    </xsl:otherwise>
                </xsl:choose> 
            </title>
        </xsl:for-each>
        <!-- TITLE -->
        
        
        <!-- CONTRIBUTOR (vcetne contributor.author = creator)-->
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='contributor']/doc:element/doc:element/doc:field">
            
    
                <xsl:choose>
                    <xsl:when test="../../@name='advisor'">
                        <advisor>
                            <xsl:value-of select="."/>
                        </advisor>
                    </xsl:when>
                    <xsl:when test="../../@name='referee'">
                        <referee>
                            <xsl:value-of select="."/>
                        </referee>
                    </xsl:when>
                    <xsl:when test="../../@name='author'">
                        <creator>
                            <xsl:value-of select="."/>
                        </creator>
                    </xsl:when>
                    <xsl:otherwise>
                        <contributor>
                            <xsl:value-of select="."/>
                        </contributor>
                    </xsl:otherwise>
                </xsl:choose>
                
                
            
        </xsl:for-each>
        
        <!-- CONTRIBUTOR -->
        
        <!-- DATE -->
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='date']/doc:element/doc:element/doc:field">
        
            <xsl:choose>
                <xsl:when test="../../@name='accessioned'">
                    <!--<accessioned>
                        <xsl:value-of select="."/>
                    </accessioned>-->    
                </xsl:when>
                <xsl:when test="../../@name='available'">
                    <available>
                        <xsl:value-of select="."/>
                    </available>    
                </xsl:when>
                <xsl:when test="../../@name='created'">
                    <created>
                        <xsl:value-of select="."/>
                    </created>    
                </xsl:when>
                <xsl:when test="../../@name='issued'">
                    <issued>
                        <xsl:value-of select="."/>
                    </issued>    
                </xsl:when>
            </xsl:choose>
            
            
                
            
        
        </xsl:for-each>
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='dateAccepted']/doc:element">
            <dateAccepted>
                <xsl:value-of select="./doc:field"/>
            </dateAccepted>
        </xsl:for-each>
        
        <!-- DATE -->
        
        
        <!-- IDENTIFIER -->
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='identifier']/doc:element">
            
            <xsl:choose>
                <xsl:when test="@name='citation'">
                    <citation>
                        <xsl:value-of select="./doc:element/doc:field"/>
                    </citation>    
                </xsl:when>
                <xsl:when test="@name='other'">
                    <identifier>
                        <xsl:value-of select="./doc:element/doc:field"/>
                    </identifier>    
                </xsl:when>
                <xsl:when test="@name='uri'">
                    <uri>
                        <xsl:value-of select="./doc:element/doc:field"/>
                    </uri>    
                </xsl:when>
                <xsl:when test="@name='issn'">
                    <issn>
                        <xsl:value-of select="./doc:element/doc:field"/>
                    </issn>    
                </xsl:when>
                <xsl:when test="@name='isbn'">
                    <isbn>
                        <xsl:value-of select="./doc:element/doc:field"/>
                    </isbn>    
                </xsl:when>
                <xsl:when test="@name='doi'">
                    <doi>
                        <xsl:value-of select="./doc:element/doc:field"/>
                    </doi>    
                </xsl:when>
                <xsl:otherwise>
                    <identifier>
                        <xsl:value-of select="./doc:element/doc:field"/>
                    </identifier>
                </xsl:otherwise>
            </xsl:choose>
            
            
       </xsl:for-each>
        
       <!-- IDENTIFIER --> 
        
        
        <!-- DESCRIPTION  -->
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='description']/doc:element">
            
            <xsl:choose>
                <xsl:when test="@name='abstract'">
                    
                    <xsl:for-each select="./doc:element"> <!-- trosku jinak kvuli dvojjazycnosti -->
                        <description>
                            <xsl:value-of select="./doc:field"/>
                        </description>
                    </xsl:for-each>
                </xsl:when>
                <xsl:when test="@name='provenance'"/>
                <xsl:when test="@name='mark'"/>
                <xsl:otherwise>
                    <description>
                        <xsl:value-of select="./doc:element/doc:field"/>
                    </description>
                </xsl:otherwise>    
                
            </xsl:choose>
            
        </xsl:for-each>     
        
        <!-- DESCRIPTION -->

        
        <!-- LANGUAGE -->
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='language']/doc:element">
            <language>
                <xsl:value-of select="./doc:element/doc:field"/>
            </language>
        </xsl:for-each>
        
        <!-- LANGUAGE -->
        
        
        <!-- SUBJECT -->
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='subject']/doc:element">
            
            <xsl:for-each select="./doc:field"> <!-- trosku jinak kvuli dvojjazycnosti -->
                <subject>
                    <xsl:value-of select="."/>
                </subject>
            </xsl:for-each>
        </xsl:for-each>

        <!-- SUBJECT -->
        
        <!-- PUBLISHER -->
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='publisher']/doc:element">
            <publisher>
                <xsl:choose>
                
                <xsl:when test="local-name(*[1])='field'">
                    <xsl:value-of select="./doc:field"/>
                    
                </xsl:when>
                <xsl:otherwise>
                    <xsl:value-of select="./doc:element/doc:field"/>        
                </xsl:otherwise>
                </xsl:choose> 
            </publisher>
        </xsl:for-each>
        
        <!-- PUBLISHER -->
        
        
        <!-- TYPE -->
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='type']/doc:element">
            <type>
                
                <xsl:choose>
                    
                    <xsl:when test="local-name(*[1])='field'">
                        <xsl:value-of select="./doc:field"/>
                
                   </xsl:when>
                    <xsl:otherwise>
                        <xsl:value-of select="./doc:element/doc:field"/>        
                    </xsl:otherwise>
                </xsl:choose> 
            </type>
        </xsl:for-each>

        <!-- TYPE -->
        
        <!-- COVERAGE -->
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='coverage']">
            
            <coverage>
                <xsl:for-each select="doc:element[@name='volume']">
                    <xsl:text>Roč. </xsl:text><xsl:value-of select="./doc:element/doc:field"/><xsl:text>, </xsl:text>
                </xsl:for-each>
                <xsl:for-each select="doc:element[@name='issue']">
                    <xsl:text>č. </xsl:text><xsl:value-of select="./doc:element/doc:field"/>
                </xsl:for-each>
            </coverage>
        
        </xsl:for-each>
        
        <!-- COVERAGE -->

        <!-- RELATION -->
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='relation']">
            <relation>
                <xsl:for-each select="doc:element[@name='ispartof']">
                    <xsl:value-of select="./doc:element/doc:field"/><xsl:text> </xsl:text>
                </xsl:for-each>
                <xsl:for-each select="doc:element[@name='uri']">
                    <xsl:value-of select="./doc:element/doc:field"/><xsl:text> </xsl:text>
                </xsl:for-each>
            </relation>
        
        </xsl:for-each>
        <!-- RELATION -->
        
        <!-- THESIS tagy -->
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='discipline']/doc:element">
            <discipline>
                <xsl:value-of select="./doc:field"/>
            </discipline>
        </xsl:for-each>
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='grantor']/doc:element">
            <grantor>
                <xsl:value-of select="./doc:field"/>
            </grantor>
        </xsl:for-each>

        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='level']/doc:element">
            <level>
                <xsl:value-of select="./doc:field"/>
            </level>
        </xsl:for-each>
        
        <!-- THESIS tagy -->
        
        <!-- Rozdeleni CITATION na jednotlive segmenty (drive zobrazena data se zde mohou duplikovat) -->
        
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='date']/doc:element[@name='issued']/doc:element">
            <year>
                <xsl:value-of select="substring(./doc:field,1,4)"/>
            </year>
        </xsl:for-each>
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='relation']/doc:element[@name='ispartof']/doc:element">
            <journal>
                <xsl:value-of select="./doc:field"/>
            </journal>
        </xsl:for-each>
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='coverage']/doc:element[@name='volume']/doc:element">
            <volume>
                <xsl:value-of select="./doc:field"/>
            </volume>
        </xsl:for-each>
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='coverage']/doc:element[@name='issue']/doc:element">
            <issue>
                <xsl:value-of select="./doc:field"/>
            </issue>
        </xsl:for-each>
        <xsl:for-each select="doc:metadata/doc:element/doc:element[@name='format']/doc:element[@name='extent']/doc:element">
            <extent>
                <xsl:value-of select="./doc:field"/>
            </extent>
        </xsl:for-each>
        
        <!-- Rozdeleni CITATION -->
    
    </qdc_vut>
    
    </xsl:template>
    
</xsl:stylesheet>
