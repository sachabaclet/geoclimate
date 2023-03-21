package org.orbisgis.geoclimate.geoindicators

import groovy.transform.BaseScript
import org.orbisgis.data.jdbc.JdbcDataSource
import org.orbisgis.geoclimate.Geoindicators

@BaseScript Geoindicators geoindicators


/**
 * This process is used to format a population table given by the user
 *
 * @param populationTable name of the population table
 * @param populationColumns the list of the columns to keep
 * @param zoneTable name of a zone table to limit the population table
 * @param datasource a connexion to the database
 * @return the name of the population table
 */
String formatPopulationTable(JdbcDataSource datasource, String populationTable, List populationColumns = [],
                             String zoneTable = "") {
    def tablePopulation_tmp = postfix(populationTable)
    if (zoneTable) {
        datasource.execute("""
                CREATE SPATIAL INDEX ON $populationTable(the_geom);
                CREATE SPATIAL INDEX ON $zoneTable(the_geom);
                DROP TABLE IF EXISTS $tablePopulation_tmp ;
                CREATE TABLE $tablePopulation_tmp AS SELECT ROWNUM() as id_pop, ST_MAKEVALID(a.the_geom) as the_geom , 
                ${populationColumns.join(",")} from $populationTable as a ,$zoneTable as b where a.the_geom && b.the_geom 
                and st_intersects(a.the_geom, b.the_geom) ;                
                DROP TABLE IF EXISTS $populationTable;
                ALTER TABLE $tablePopulation_tmp rename to $populationTable;
                """.toString())
    } else {
        datasource.execute("""
                DROP TABLE IF EXISTS $tablePopulation_tmp ;
                CREATE TABLE $tablePopulation_tmp AS SELECT rownum() as id_pop, ST_MAKEVALID(the_geom) as the_geom , 
                ${populationColumns.join(",")} from $populationTable ;
                DROP TABLE IF EXISTS $populationTable;
                ALTER TABLE $tablePopulation_tmp rename to $populationTable;
                """.toString())
    }
    return populationTable
}

/**
 * This process is used to aggregate population data at multiscales
 *
 * @param inputPopulation name of the population table
 * @param inputPopulationColumns the list of the columns to keep
 * @param inputBuildingTableName name of the building table to distribute the population columns
 * @param inputRsuTableName name of the RSU table to distribute the population columns
 * @param inputGridTableName name of the GRID table to distribute the population columns
 * @param datasource a connexion to the database that contains the input data
 *
 * @return a map with the population distributed on building, rsu or grid
 */
Map multiScalePopulation(JdbcDataSource datasource, String populationTable, List populationColumns = [],
                         String buildingTable, String rsuTable, String gridTable) {
    if (populationTable && populationColumns) {
        def prefixName = "pop"
        if (buildingTable) {
            String buildingPop = Geoindicators.BuildingIndicators.buildingPopulation(datasource, buildingTable,
                    populationTable, populationColumns)
            if (buildingPop) {
                datasource.execute("""DROP TABLE IF EXISTS $buildingTable;
                                ALTER TABLE ${buildingPop} RENAME TO $buildingTable""".toString())
                if (rsuTable) {
                    def unweightedBuildingIndicators = [:]
                    populationColumns.each { col ->
                        unweightedBuildingIndicators.put(col, ["sum"])
                    }
                    def rsu_pop_tmp = Geoindicators.GenericIndicators.unweightedOperationFromLowerScale(datasource,
                            buildingTable, rsuTable,
                            "id_rsu", "id_rsu",
                            unweightedBuildingIndicators, prefixName,)
                    if (rsu_pop_tmp) {
                        def rsu_pop_geom = postfix(rsuTable)
                        def tablesToJoin = [:]
                        tablesToJoin.put(rsuTable, "id_rsu")
                        tablesToJoin.put(rsu_pop_tmp, "id_rsu")
                        def p = Geoindicators.DataUtils.joinTables(datasource,
                                tablesToJoin, rsu_pop_geom)
                        if (p) {
                            datasource.execute("""DROP TABLE IF EXISTS $rsuTable, $rsu_pop_tmp;
                                    ALTER TABLE $rsu_pop_geom RENAME TO $rsuTable;
                                    DROP TABLE IF EXISTS $rsu_pop_geom;""".toString())
                        }

                    }
                }
                if (gridTable) {
                    def sum_popColumns = []
                    def popColumns = []
                    populationColumns.each { col ->
                        sum_popColumns << "sum($col*area_intersects/area_building) as SUM_$col"
                        popColumns << "b.${col}"
                    }
                    //Here the population from the building to a grid
                    // Create the relations between grid cells and buildings
                    datasource."$buildingTable".the_geom.createSpatialIndex()
                    datasource."$gridTable".the_geom.createSpatialIndex()
                    def buildingGrid_inter = postfix("building_grid_inter")
                    datasource.execute("""
                                DROP TABLE IF EXISTS $buildingGrid_inter;
                                CREATE TABLE $buildingGrid_inter AS SELECT ST_AREA(ST_INTERSECTION(a.the_geom, b.the_geom)) 
                                AS area_intersects, st_area(b.the_geom) as area_building, ${popColumns.join(",")},a.id_grid from $gridTable as a, $buildingTable as b
                                where a.the_geom && b.the_geom and ST_Intersects(a.the_geom, b.the_geom);                                
                            """.toString())

                    //Compute the population by cells
                    def buildingPopGrid_tmp = postfix("building_grid_pop_tmp")
                    def buildingPopGrid = postfix("building_grid_pop")
                    datasource.execute("""DROP TABLE IF EXISTS $buildingPopGrid_tmp, $buildingPopGrid;
                                CREATE INDEX ON $buildingGrid_inter (ID_GRID);
                                CREATE TABLE $buildingPopGrid_tmp AS SELECT ${sum_popColumns.join(",")}, ID_GRID FROM ${buildingGrid_inter} GROUP BY ID_GRID;
                                CREATE INDEX ON $buildingPopGrid_tmp (ID_GRID);
                                CREATE TABLE $buildingPopGrid AS SELECT a.*, b.* EXCEPT(ID_GRID) from $gridTable a  
                                LEFT JOIN $buildingPopGrid_tmp  b on a.ID_GRID=b.ID_GRID;
                                DROP TABLE IF EXISTS $buildingPopGrid_tmp, $gridTable;
                                ALTER TABLE $buildingPopGrid RENAME TO $gridTable;
                                DROP TABLE IF EXISTS $buildingPopGrid;
                                """.toString())
                }

            }
        }
        return ["buildingTable": buildingTable, "rsuTable": rsuTable, "gridTable": gridTable]

    }
    warn "Please set a valid population table name and a list of population columns"
    return
}
