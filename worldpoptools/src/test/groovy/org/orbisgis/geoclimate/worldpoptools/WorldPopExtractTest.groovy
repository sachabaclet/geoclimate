package org.orbisgis.geoclimate.worldpoptools

import org.h2gis.api.EmptyProgressVisitor
import org.h2gis.functions.io.asc.AscReaderDriver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.orbisgis.orbisdata.datamanager.jdbc.h2gis.H2GIS
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.orbisgis.orbisdata.datamanager.jdbc.h2gis.H2GIS.open

class WorldPopExtractTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldPopExtractTest)
    private static H2GIS h2GIS

    @BeforeAll
    static void beforeAll(){
        h2GIS = open"./target/worldpop_extract;AUTO_SERVER=TRUE"
    }

    @BeforeEach
    final void beforeEach(TestInfo testInfo){
        LOGGER.info("@ ${testInfo.testMethod.get().name}()")
    }

    @AfterEach
    final void afterEach(TestInfo testInfo){
        LOGGER.info("# ${testInfo.testMethod.get().name}()")
    }

    /**
     * Test to extract a grid as an ascii file and load it in H2GIS
     */
    @Test
    void extractGrid(){
        def outputGridFile = new File("target/extractGrid.asc")
        if(outputGridFile.exists()){
            outputGridFile.delete()
        }
        def coverageId = "wpGlobal:ppp_2018"
        def bbox =[ 47.63324, -2.78087,47.65749, -2.75979]
        assertTrue WorldPopTools.Extract.grid(coverageId, bbox,4326, outputGridFile)

        AscReaderDriver ascReaderDriver = new AscReaderDriver()
        ascReaderDriver.setAs3DPoint(false)
        ascReaderDriver.setEncoding("UTF-8")
        ascReaderDriver.setDeleteTable(true)
        ascReaderDriver.read(h2GIS.getConnection(),outputGridFile, new EmptyProgressVisitor(), "grid", 4326)
        assertEquals(720, h2GIS.getSpatialTable("grid").rowCount)
    }
}
