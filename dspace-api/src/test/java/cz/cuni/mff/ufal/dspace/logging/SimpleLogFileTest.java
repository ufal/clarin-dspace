/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
/* Created for LINDAT/CLARIN */
package cz.cuni.mff.ufal.dspace.logging;

import java.io.File;
import java.net.URL;

import static org.junit.Assert.*;

import org.junit.*;

public class SimpleLogFileTest
{
   
    @Test
    public void testLogEntriesCount()
    {
        String filePath = SimpleLogFileTest.class.getClassLoader().getResource("./").getPath() + "../testing/dspace/log/dspace.log";
        File file = new File(filePath);
        
        assertTrue("Test log file not found", file.exists());
        assertTrue("Test log file not readable", file.canRead());
        
        SimpleLogFile logFile = new SimpleLogFile(file);
        int i = 0;
        for (SimpleLogEntry logEntry : logFile)
        {
            i++;
        }
        assertEquals("Log entries count failed", 77, i);
    }

}
