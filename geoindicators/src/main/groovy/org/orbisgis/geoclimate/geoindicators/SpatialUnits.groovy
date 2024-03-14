/**
 * GeoClimate is a geospatial processing toolbox for environmental and climate studies
 * <a href="https://github.com/orbisgis/geoclimate">https://github.com/orbisgis/geoclimate</a>.
 *
 * This code is part of the GeoClimate project. GeoClimate is free software;
 * you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation;
 * version 3.0 of the License.
 *
 * GeoClimate is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details <http://www.gnu.org/licenses/>.
 *
 *
 * For more information, please consult:
 * <a href="https://github.com/orbisgis/geoclimate">https://github.com/orbisgis/geoclimate</a>
 *
 */
package org.orbisgis.geoclimate.geoindicators

import groovy.transform.BaseScript
import org.h2.value.ValueGeometry
import org.h2gis.functions.spatial.create.ST_MakeGrid
import org.locationtech.jts.geom.Geometry
import org.orbisgis.data.H2GIS
import org.orbisgis.data.POSTGIS
import org.orbisgis.data.jdbc.JdbcDataSource
import org.orbisgis.geoclimate.Geoindicators

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

import static org.h2gis.network.functions.ST_ConnectedComponents.getConnectedComponents

@BaseScript Geoindicators geoindicators

/**
 * This process is used to create the Topographical Spatial Units used reference spatial units (RSU)
 *
 * @param inputzone The zone table to keep the RSU inside
 * Default value is empty so all RSU are kept.
 * @param prefixName A prefix used to name the output table
 * @param datasource A connection to a database
 *
 * @param area TSU less or equals than area are removed, default 1d
 * @param road The road table to be processed
 * @param rail The rail table to be processed
 * @param vegetation The vegetation table to be processed
 * @param water The water table to be processed
 * @param sea_land_mask The table to distinguish sea from land
 * @param urban_areas The table to distinguish the urban areas
 * @param surface_vegetation A double value to select the vegetation geometry areas.
 * Expressed in geometry unit of the vegetationTable, default 10000
 * @param surface_hydro A double value to select the hydrographic geometry areas.
 * Expressed in geometry unit of the vegetationTable, default 2500
 * @param surface_urban_areas A double value to select the urban  areas.
 * Expressed in geometry unit of the urban_areas table, default 10000
 *
 * @return A database table name and the name of the column ID
 */
String createTSU(JdbcDataSource datasource, String zone,
                 double area = 1f, String road, String rail, String vegetation,
                 String water, String sea_land_mask, String urban_areas,
                 double surface_vegetation, double surface_hydro, double surface_urban_areas, String prefixName) {
    def BASE_NAME = "rsu"

    debug "Creating the reference spatial units"
    // The name of the outputTableName is constructed
    def outputTableName = prefix prefixName, BASE_NAME
    datasource """DROP TABLE IF EXISTS $outputTableName;""".toString()

    def tsuDataPrepared = prepareTSUData(datasource,
            zone, road, rail,
            vegetation, water, sea_land_mask, urban_areas, surface_vegetation, surface_hydro, surface_urban_areas, prefixName)
    if (!tsuDataPrepared) {
        info "Cannot prepare the data for RSU calculation."
        return
    }
    def outputTsuTableName = Geoindicators.SpatialUnits.createTSU(datasource, tsuDataPrepared, zone,
            area, prefixName)
    if (!outputTsuTableName) {
        info "Cannot compute the RSU."
        return
    }
    datasource.dropTable(tsuDataPrepared)

    datasource """ALTER TABLE $outputTsuTableName RENAME TO $outputTableName;""".toString()

    debug "Reference spatial units table created"

    return outputTableName
}

/**
 * This process is used to create the Topographical Spatial Units (TSU)
 *
 * @param inputTableName The input spatial table to be processed
 * @param inputzone The zone table to keep the TSU inside
 * Default value is empty so all TSU are kept.
 * @param prefixName A prefix used to name the output table
 * @param datasource A connection to a database
 * @param area TSU less or equals than area are removed
 * @return A database table name and the name of the column ID
 */
String createTSU(JdbcDataSource datasource, String inputTableName, String inputzone, double area = 1d, String prefixName) {
    def COLUMN_ID_NAME = "id_rsu"
    def BASE_NAME = "tsu"

    debug "Creating the reference spatial units"
    // The name of the outputTableName is constructed
    def outputTableName = prefix prefixName, BASE_NAME

    if (!inputTableName) {
        error "The input data to compute the TSU cannot be null or empty"
        return null
    }
    def epsg = datasource.getSrid(inputTableName)

    if (area <= 0) {
        error "The area value to filter the TSU must be greater to 0"
        return null
    }
    if (inputzone) {
        datasource.createSpatialIndex(inputTableName, "the_geom")
        datasource """
                    DROP TABLE IF EXISTS $outputTableName;
                    CREATE TABLE $outputTableName AS 
                        SELECT EXPLOD_ID AS $COLUMN_ID_NAME, ST_SETSRID(ST_BUFFER(a.the_geom,0.01), $epsg) AS the_geom
                        FROM ST_EXPLODE('(
                                SELECT ST_POLYGONIZE(ST_UNION(ST_NODE(ST_ACCUM(the_geom)))) AS the_geom 
                                FROM $inputTableName)') AS a,
                            $inputzone AS b
                        WHERE a.the_geom && b.the_geom 
                        AND ST_INTERSECTS(ST_POINTONSURFACE(a.THE_GEOM), b.the_geom) and st_area(a.the_geom) > $area
            """.toString()
    } else {
        datasource """
                    DROP TABLE IF EXISTS $outputTableName;
                    CREATE TABLE $outputTableName AS 
                        SELECT EXPLOD_ID AS $COLUMN_ID_NAME, ST_SETSRID(st_buffer(the_geom, -0.01), $epsg) AS the_geom 
                        FROM ST_EXPLODE('(
                                SELECT ST_POLYGONIZE(ST_UNION(ST_NODE(ST_ACCUM(the_geom)))) AS the_geom 
                                FROM $inputTableName)') where st_area(the_geom) > $area""".toString()
    }

    debug "Reference spatial units table created"

    return outputTableName
}

/**
 * This process is used to prepare the input abstract model
 * in order to compute the Topographical Spatial Units (TSU)
 *
 * @param zone The area of zone to be processed
 * @param road The road table to be processed
 * @param rail The rail table to be processed
 * @param vegetation The vegetation table to be processed
 * @param water The hydrographic table to be processed
 * @param water The sea mask to be processed
 * @param water The urban areas table to be processed
 * @param surface_vegetation A double value to select the vegetation geometry areas.
 * Expressed in geometry unit of the vegetationTable. 10000 m² seems correct.
 * @param sea_land_mask The table to distinguish sea from land
 * @param surface_hydro A double value to select the hydrographic geometry areas.
 * Expressed in geometry unit of the vegetationTable. 2500 m² seems correct.
 * @param prefixName A prefix used to name the output table
 * @param datasource A connection to a database
 * @param outputTableName The name of the output table
 * @return A database table name.
 */
String prepareTSUData(JdbcDataSource datasource, String zone, String road, String rail,
                      String vegetation, String water, String sea_land_mask, String urban_areas,
                      double surface_vegetation, double surface_hydro, double surface_urban_areas, String prefixName = "unified_abstract_model") {

    if (surface_vegetation <= 100) {
        error("The surface of vegetation must be greater or equal than 100 m²")
        return
    }
    if (surface_hydro <= 100) {
        error("The surface of water must be greater or equal than 100 m²")
        return
    }

    if (surface_urban_areas <= 100) {
        error("The surface of urban areas must be greater or equal than 100 m²")
        return
    }

    def BASE_NAME = "prepared_tsu_data"

    debug "Preparing the abstract model to build the TSU"

    // The name of the outputTableName is constructed
    def outputTableName = prefix prefixName, BASE_NAME

    // Create temporary table names (for tables that will be removed at the end of the method)
    def vegetation_tmp
    def hydrographic_tmp

    def queryCreateOutputTable = [:]
    def dropTableList = []

    def numberZone = datasource.firstRow("SELECT COUNT(*) AS nb FROM $zone".toString()).nb

    if (numberZone == 1) {
        def epsg = datasource."$zone".srid
        //Add the land mask
        if (sea_land_mask && datasource.hasTable(sea_land_mask)) {
            debug "Preparing land mask..."
            queryCreateOutputTable += [land_mask_tmp: "(SELECT ST_ToMultiLine(THE_GEOM) FROM $sea_land_mask where type ='land')"]
        }
        if (vegetation && datasource.hasTable(vegetation)) {
            debug "Preparing vegetation..."
            if (datasource.getColumnNames(vegetation)) {
                vegetation_tmp = postfix "vegetation_tmp"
                def vegetation_graph = postfix "vegetation_graph"
                def subGraphTableNodes = postfix vegetation_graph, "NODE_CC"
                def subGraphTableEdges = postfix vegetation_graph, "EDGE_CC"
                def subGraphBlocksLow = postfix "subgraphblocks_low"
                def subGraphBlocksHigh = postfix "subgraphblocks_high"

                datasource "DROP TABLE IF EXISTS   $vegetation_tmp, $vegetation_graph, $subGraphTableNodes, $subGraphTableEdges, $subGraphBlocksLow, $subGraphBlocksHigh".toString()

                datasource.createIndex(vegetation, "ID_VEGET")
                datasource.createSpatialIndex(vegetation, "THE_GEOM")
                datasource.execute """          
                   CREATE TABLE $vegetation_graph (EDGE_ID SERIAL, START_NODE INT, END_NODE INT) AS 
                   SELECT CAST((row_number() over()) as Integer), a.ID_VEGET as START_NODE, b.ID_VEGET AS END_NODE 
                   FROM $vegetation  AS a, $vegetation AS b 
                   WHERE a.ID_VEGET <>b.ID_VEGET AND a.the_geom && b.the_geom 
                   AND ST_INTERSECTS(b.the_geom,a.the_geom);
                   """.toString()

                //Recherche des clusters
                getConnectedComponents(datasource.getConnection(), vegetation_graph, "undirected")

                //Unify water geometries that share a boundary
                debug "Merging spatial clusters..."
                //Processing low vegetation
                datasource """
                CREATE INDEX ON $subGraphTableNodes (NODE_ID);
                CREATE TABLE $subGraphBlocksLow AS SELECT ST_ToMultiLine(ST_UNION(ST_ACCUM(A.THE_GEOM))) AS THE_GEOM
                FROM $vegetation A, $subGraphTableNodes B
                WHERE a.ID_VEGET=b.NODE_ID AND a.HEIGHT_CLASS= 'low' GROUP BY B.CONNECTED_COMPONENT 
                HAVING SUM(st_area(A.THE_GEOM)) >= $surface_vegetation;""".toString()

                //Processing high vegetation
                datasource """
                CREATE TABLE $subGraphBlocksHigh AS SELECT ST_ToMultiLine(ST_UNION(ST_ACCUM(A.THE_GEOM))) AS THE_GEOM
                FROM $vegetation A, $subGraphTableNodes B
                WHERE a.ID_VEGET=b.NODE_ID AND a.HEIGHT_CLASS= 'high' GROUP BY B.CONNECTED_COMPONENT 
                HAVING SUM(st_area(A.THE_GEOM)) >= $surface_vegetation;""".toString()

                debug "Creating the vegetation block table..."

                datasource """DROP TABLE IF EXISTS $vegetation_tmp; 
                CREATE TABLE $vegetation_tmp (THE_GEOM GEOMETRY) 
                AS SELECT the_geom FROM $subGraphBlocksLow
                UNION ALL SELECT the_geom FROM $subGraphBlocksHigh
                UNION ALL SELECT  ST_ToMultiLine(a.the_geom) as the_geom FROM $vegetation a 
                LEFT JOIN $subGraphTableNodes b ON a.ID_VEGET = b.NODE_ID WHERE b.NODE_ID IS NULL 
                AND st_area(a.the_geom)>=$surface_vegetation;
                DROP TABLE $subGraphTableNodes,$subGraphTableEdges, $vegetation_graph, $subGraphBlocksLow, $subGraphBlocksHigh;""".toString()
                queryCreateOutputTable += [vegetation_tmp: "(SELECT the_geom FROM $vegetation_tmp)"]
                dropTableList.addAll([vegetation_tmp])
            }
        }

        if (water && datasource.hasTable(water)) {
            if (datasource.getColumnNames(water).size() > 0) {
                //Extract water
                debug "Preparing hydrographic..."
                hydrographic_tmp = postfix "hydrographic_tmp"
                def water_graph = postfix "water_graphes"
                def subGraphTableNodes = postfix water_graph, "NODE_CC"
                def subGraphTableEdges = postfix water_graph, "EDGE_CC"
                def subGraphBlocks = postfix "subgraphblocks"

                datasource "DROP TABLE IF EXISTS  $hydrographic_tmp, $water_graph, $subGraphTableNodes, $subGraphTableEdges, $subGraphBlocks".toString()

                datasource.createIndex(water, "ID_WATER")
                datasource.createSpatialIndex(water, "THE_GEOM")
                datasource.execute """          
                   CREATE TABLE $water_graph (EDGE_ID SERIAL, START_NODE INT, END_NODE INT) AS 
                   SELECT CAST((row_number() over()) as Integer), a.ID_WATER as START_NODE, b.ID_WATER AS END_NODE 
                   FROM $water  AS a, $water AS b 
                   WHERE a.ID_WATER <>b.ID_WATER AND a.the_geom && b.the_geom 
                   AND ST_INTERSECTS(b.the_geom,a.the_geom) and a.ZINDEX=0;
                   """.toString()

                //Recherche des clusters
                getConnectedComponents(datasource.getConnection(), water_graph, "undirected")

                //Unify water geometries that share a boundary
                debug "Merging spatial clusters..."

                datasource """
                CREATE INDEX ON $subGraphTableNodes (NODE_ID);
                CREATE TABLE $subGraphBlocks AS SELECT ST_ToMultiLine(ST_UNION(ST_ACCUM(A.THE_GEOM))) AS THE_GEOM
                FROM $water A, $subGraphTableNodes B
                WHERE a.ID_WATER=b.NODE_ID GROUP BY B.CONNECTED_COMPONENT 
                HAVING SUM(st_area(A.THE_GEOM)) >= $surface_hydro;""".toString()
                debug "Creating the water block table..."
                datasource """DROP TABLE IF EXISTS $hydrographic_tmp; 
                CREATE TABLE $hydrographic_tmp (THE_GEOM GEOMETRY) 
                AS SELECT the_geom FROM $subGraphBlocks
                UNION ALL SELECT ST_ToMultiLine(a.the_geom) as the_geom  FROM $water a 
                LEFT JOIN $subGraphTableNodes b ON a.ID_WATER = b.NODE_ID WHERE b.NODE_ID IS NULL and 
                st_area(a.the_geom)>=$surface_hydro;
                DROP TABLE $subGraphTableNodes,$subGraphTableEdges, $water_graph, $subGraphBlocks ;""".toString()

                queryCreateOutputTable += [hydrographic_tmp: "(SELECT the_geom FROM $hydrographic_tmp)"]
                dropTableList.addAll([hydrographic_tmp])
            }
        }

        if (road && datasource.hasTable(road)) {
            debug "Preparing road..."
            if (datasource.getColumnNames(road).size() > 0) {
                queryCreateOutputTable += [road_tmp: "(SELECT ST_ToMultiLine(THE_GEOM) FROM $road where (zindex=0 or crossing in ('bridge', 'crossing')) " +
                        "and type not in ('track','service', 'path', 'cycleway', 'steps'))"]
            }
        }

        if (rail && datasource.hasTable(rail)) {
            debug "Preparing rail..."
            if (datasource.getColumnNames(rail).size() > 0) {
                queryCreateOutputTable += [rail_tmp: "(SELECT ST_ToMultiLine(THE_GEOM) FROM $rail where (zindex=0 and usage='main') or (crossing = 'bridge' and usage='main'))"]
            }
        }

        if (water && datasource.hasTable(urban_areas)) {
            if (datasource.getColumnNames(urban_areas).size() > 0) {
                debug "Preparing urban areas..."
                queryCreateOutputTable += [urban_areas_tmp: "(SELECT ST_ToMultiLine(THE_GEOM) FROM $urban_areas WHERE st_area(the_geom)>=$surface_urban_areas and type not in ('social_building'))"]
            }
        }

        // The input table that contains the geometries to be transformed as TSU
        debug "Grouping all tables..."
        if (queryCreateOutputTable) {
            datasource """
                        DROP TABLE if exists $outputTableName;
                        CREATE TABLE $outputTableName(the_geom GEOMETRY) AS 
                            (
                                SELECT st_setsrid(ST_ToMultiLine(THE_GEOM),$epsg) as the_geom
                                FROM $zone) 
                            UNION ${queryCreateOutputTable.values().join(' union ')};
                        DROP TABLE IF EXISTS ${queryCreateOutputTable.keySet().join(' , ')}
                """.toString()
        } else {
            datasource """DROP TABLE if exists $outputTableName;
            CREATE TABLE $outputTableName(the_geom GEOMETRY) AS (SELECT st_setsrid(ST_ToMultiLine(THE_GEOM),$epsg) 
            FROM $zone);""".toString()
        }
        if (dropTableList) {
            datasource "DROP TABLE IF EXISTS ${dropTableList.join(',')};".toString()
        }
        debug "TSU created..."

    } else {
        error "Cannot compute the TSU. The input zone table must have one row."
        outputTableName = null
    }
    return outputTableName
}

/**
 * This process is used to merge the geometries that touch each other
 *
 * @param datasource A connexion to a database (H2GIS, PostGIS, ...) where are stored the input Table and in which
 * the resulting database will be stored
 * @param inputTableName The input table tos create the block (group of geometries)
 * @param snappingTolerance A distance to group the geometries
 * @param prefixName A prefix used to name the output table
 * @param outputTableName The name of the output table
 * @return A database table name and the name of the column ID
 */
String createBlocks(JdbcDataSource datasource, String inputTableName, double snappingTolerance = 0.0d, String prefixName = "block") {

    def BASE_NAME = "blocks"

    debug "Creating the blocks..."

    def columnIdName = "id_block"

    // The name of the outputTableName is constructed
    def outputTableName = prefix prefixName, BASE_NAME
    //Find all neighbors for each building
    debug "Building index to perform the process..."
    datasource.createSpatialIndex(inputTableName, "the_geom")
    datasource.createIndex(inputTableName, "id_build")

    debug "Building spatial clusters..."

    // Create temporary table names (for tables that will be removed at the end of the method)
    def graphTable = postfix "spatial_clusters"
    def subGraphTableNodes = postfix graphTable, "NODE_CC"
    def subGraphTableEdges = postfix graphTable, "EDGE_CC"
    def subGraphBlocks = postfix "subgraphblocks"

    datasource """
                DROP TABLE IF EXISTS $graphTable; 
                CREATE TABLE $graphTable (EDGE_ID SERIAL, START_NODE INT, END_NODE INT) AS 
                    SELECT CAST((row_number() over()) as Integer), a.id_build as START_NODE, b.id_build AS END_NODE 
                    FROM $inputTableName AS a, $inputTableName AS b 
                    WHERE a.id_build<>b.id_build AND a.the_geom && b.the_geom 
                    AND ST_DWITHIN(b.the_geom,a.the_geom, $snappingTolerance);
        """.toString()

    datasource "DROP TABLE IF EXISTS $subGraphTableEdges, $subGraphTableNodes;".toString()

    getConnectedComponents(datasource.getConnection(), graphTable, "undirected")

    //Unify buildings that share a boundary
    debug "Merging spatial clusters..."

    if (snappingTolerance > 0) {
        datasource """
                    CREATE INDEX ON $subGraphTableNodes (NODE_ID);
                    DROP TABLE IF EXISTS $subGraphBlocks;
                    CREATE TABLE $subGraphBlocks AS
                        SELECT ST_UNION(ST_ACCUM(ST_buffer(A.THE_GEOM, $snappingTolerance))) AS THE_GEOM
                        FROM $inputTableName A, $subGraphTableNodes B
                        WHERE A.id_build=B.NODE_ID GROUP BY B.CONNECTED_COMPONENT;
            """.toString()
    } else {
        datasource """
        CREATE INDEX ON $subGraphTableNodes (NODE_ID);
        DROP TABLE IF EXISTS $subGraphBlocks;
        CREATE TABLE $subGraphBlocks
        AS SELECT ST_UNION(ST_ACCUM(ST_MAKEVALID(A.THE_GEOM))) AS THE_GEOM
        FROM $inputTableName A, $subGraphTableNodes B
        WHERE A.id_build=B.NODE_ID GROUP BY B.CONNECTED_COMPONENT;""".toString()
    }
    //Create the blocks
    debug "Creating the block table..."

    datasource """DROP TABLE IF EXISTS $outputTableName; 
        CREATE TABLE $outputTableName ($columnIdName SERIAL, THE_GEOM GEOMETRY) 
        AS SELECT CAST((row_number() over()) as Integer), the_geom FROM 
        ((SELECT st_force2d(ST_MAKEVALID(THE_GEOM)) as the_geom FROM $subGraphBlocks) 
        UNION ALL (SELECT  st_force2d(ST_MAKEVALID(a.the_geom)) as the_geom FROM $inputTableName a 
        LEFT JOIN $subGraphTableNodes b ON a.id_build = b.NODE_ID WHERE b.NODE_ID IS NULL));""".toString()

    // Temporary tables are deleted
    datasource """DROP TABLE IF EXISTS  $graphTable, 
                    $subGraphBlocks, $subGraphTableNodes, $subGraphTableEdges;""".toString()

    debug "The blocks have been created"
    return outputTableName
}
/**
 * This process is used to spatially link polygons coming from two tables. It may be used to find the relationships
 * between a building and a block, a building and a RSU but also between a building from a first dataset and a building
 * from a second dataset (the datasets may come from a different data provider or from a different year).
 *
 * @param datasource A connexion to a database (H2GIS, PostGIS, ...) where are stored the input Table and in which
 * the resulting database will be stored
 * @param sourceTable A first input table where are stored polygons (note that all columns will be conserved in the resulting table)
 * @param targetTable A second input table where are stored polygons
 * @param idColumnTarget The column name where is stored the ID of table target
 * @param prefixName A prefix used to name the output table
 * @param pointOnSurface Whether or not the spatial join may be performed on pointOnSurfaceTypes of spatial join that may be used (default false):
 *          --> True: polygons from the first table are converted to points before to be spatially join with polygons from the second table
 *          --> False: polygons from the first table are directly spatially joined with polygons from the second table
 * @param nbRelations If 'pointOnSurface' is False, number of relations that one polygon in Table 2 may have with
 * polygons from Table 1 (e.g. if nbRelations = 1 for buildings and RSU, the buildings can have only one RSU as relation).
 * The selection of which polygon(s) need to be conserved is based on shared polygons area. By default, this parameter
 * has no value and thus all relations are conserved
 *
 * @return outputTableName A table name containing ID from table 1, ID from table 2 and AREA shared by the two objects (if pointOnSurface = false)
 */
String spatialJoin(JdbcDataSource datasource, String sourceTable, String targetTable,
                   String idColumnTarget, boolean pointOnSurface = false, Integer nbRelations, String prefixName) {
    def GEOMETRIC_COLUMN_SOURCE = "the_geom"
    def GEOMETRIC_COLUMN_TARGET = "the_geom"

    debug "Creating a spatial join between objects from two tables :  $sourceTable and $targetTable"

    // The name of the outputTableName is constructed (the prefix name is not added since it is already contained
    // in the inputLowerScaleTableName object
    def outputTableName = postfix "${sourceTable}_${targetTable}", "join"
    datasource.createSpatialIndex(sourceTable, "the_geom")
    datasource.createSpatialIndex(targetTable, "the_geom")

    if (pointOnSurface) {
        datasource """    DROP TABLE IF EXISTS $outputTableName;
                                CREATE TABLE $outputTableName AS SELECT a.*, b.$idColumnTarget 
                                        FROM $sourceTable a, $targetTable b 
                                        WHERE   ST_POINTONSURFACE(a.$GEOMETRIC_COLUMN_SOURCE) && b.$GEOMETRIC_COLUMN_TARGET AND 
                                                ST_INTERSECTS(ST_POINTONSURFACE(a.$GEOMETRIC_COLUMN_SOURCE), b.$GEOMETRIC_COLUMN_TARGET)""".toString()
    } else {
        if (nbRelations != null) {
            datasource """  DROP TABLE IF EXISTS $outputTableName;
                                    CREATE TABLE $outputTableName 
                                            AS SELECT   a.*, 
                                                        (SELECT b.$idColumnTarget 
                                                            FROM $targetTable b 
                                                            WHERE a.$GEOMETRIC_COLUMN_SOURCE && b.$GEOMETRIC_COLUMN_TARGET AND 
                                                                 ST_INTERSECTS(a.$GEOMETRIC_COLUMN_SOURCE, 
                                                                                            b.$GEOMETRIC_COLUMN_TARGET) 
                                                        ORDER BY ST_AREA(ST_INTERSECTION(ST_BUFFER(ST_PRECISIONREDUCER(a.$GEOMETRIC_COLUMN_SOURCE, 3),0),
                                                                                         ST_BUFFER(ST_PRECISIONREDUCER(b.$GEOMETRIC_COLUMN_TARGET,3),0)))
                                                        DESC LIMIT $nbRelations) AS $idColumnTarget 
                                            FROM $sourceTable a""".toString()
        } else {
            datasource """  DROP TABLE IF EXISTS $outputTableName;
                                    CREATE TABLE $outputTableName 
                                            AS SELECT   a.*, b.$idColumnTarget,
                                                        ST_AREA(ST_INTERSECTION(ST_PRECISIONREDUCER(a.$GEOMETRIC_COLUMN_SOURCE, 3), 
                                                        ST_PRECISIONREDUCER(b.$GEOMETRIC_COLUMN_TARGET,3))) AS AREA
                                            FROM    $sourceTable a, $targetTable b
                                            WHERE   a.$GEOMETRIC_COLUMN_SOURCE && b.$GEOMETRIC_COLUMN_TARGET AND 
                                                    ST_INTERSECTS(a.$GEOMETRIC_COLUMN_SOURCE, b.$GEOMETRIC_COLUMN_TARGET);""".toString()
        }
    }

    debug "The spatial join have been performed between :  $sourceTable and $targetTable"

    return outputTableName
}

/**
 * This process is used to generate a continuous cartesian grid
 * on which indicators have to be aggregated.
 *
 * @param geometry A geometry that defines either Point, Line or Polygon
 * @param deltaX The horizontal spatial step of a cell in meter
 * @param deltaY The vertical spatial step of a cell in meter
 * @param tableName A Table that contains the geometry of the grid
 * @param datasource A connexion to a database (H2GIS, POSTGIS, ...) where are stored the input Table and in which
 *        the resulting database will be stored
 * @return The name of the created table
 *
 * @author Emmanuel Renault, CNRS, 2020
 * */
String createGrid(JdbcDataSource datasource, Geometry geometry, double deltaX, double deltaY, boolean rowCol = false, String prefixName = "") {
    if (rowCol) {
        if (!deltaX || !deltaY || deltaX < 1 || deltaY < 1) {
            debug "Invalid grid size padding. Must be greater or equal than 1"
            return
        }
    } else {
        if (!deltaX || !deltaY || deltaX <= 0 || deltaY <= 0) {
            error "Invalid grid size padding. Must be greater than 0"
            return
        }
    }
    if (!geometry) {
        error "The envelope is null or empty. Cannot compute the grid"
        return
    }

    def BASENAME = "grid"
    def outputTableName = prefix prefixName, BASENAME
    datasource "DROP TABLE IF EXISTS $outputTableName;"

    if (datasource instanceof H2GIS) {
        debug "Creating grid with H2GIS"
        datasource """
                           CREATE TABLE $outputTableName AS SELECT the_geom, id as id_grid,ID_COL, ID_ROW FROM 
                           ST_MakeGrid(st_geomfromtext('$geometry',${geometry.getSRID()}), $deltaX, $deltaY,$rowCol);
                           """.toString()
    } else if (datasource instanceof POSTGIS) {
        debug "Creating grid with POSTGIS"
        PreparedStatement preparedStatement = null
        Connection outputConnection = datasource.getConnection()
        try {
            def createTable = "CREATE TABLE $outputTableName(THE_GEOM GEOMETRY(POLYGON), ID_GRID INT, ID_COL INT, ID_ROW INT);"
            def insertTable = "INSERT INTO $outputTableName VALUES (?, ?, ?, ?);"
            datasource.execute(createTable.toString())
            preparedStatement = outputConnection.prepareStatement(insertTable.toString())
            def result = ST_MakeGrid.createGrid(outputConnection, ValueGeometry.getFromGeometry(geometry), deltaX, deltaY, rowCol)
            long batch_size = 0
            int batchSize = 1000

            while (result.next()) {
                preparedStatement.setObject(1, result.getObject(1))
                preparedStatement.setObject(2, result.getInt(2))
                preparedStatement.setObject(3, result.getInt(3))
                preparedStatement.setObject(4, result.getInt(4))
                preparedStatement.addBatch()
                batch_size++
                if (batch_size >= batchSize) {
                    preparedStatement.executeBatch()
                    preparedStatement.clearBatch()
                    batchSize = 0;
                }
            }
            if (batch_size > 0) {
                preparedStatement.executeBatch()
            }
        } catch (SQLException e) {
            error("Cannot create the grid with the parameters.\n", e)
            return null
        } finally {
            if (preparedStatement != null) {
                preparedStatement.close()
            }
        }
    }
    debug "The table $outputTableName has been created"
    return outputTableName
}


/**
 * This method allows to compute a sprawl areas layer from a regular grid that
 * contains the fraction area of each LCZ type for each cell.
 *
 * A sprawl geometry represents a continous areas of urban LCZs (1 to 10 plus 105)
 * It is important to note that pixels that do not have at least 1 urban neighbor are not kept.
 * @param datasource connexion to the database
 * @param grid_indicators a grid that contains the LCZ fractions
 * @param distance value to erode (delete) small sprawl areas
 * @author Erwan Bocher (CNRS)
 */
String computeSprawlAreas(JdbcDataSource datasource, String grid_indicators, float distance = 100) {
    //We must compute the grid
    if (!grid_indicators) {
        error("No grid_indicators table to compute the sprawl areas layer")
        return
    }
    if (distance < 0) {
        error("Please set a distance greater or equal than 0")
        return
    }
    if (datasource.getRowCount(grid_indicators) == 0) {
        error("No grid cells to compute the sprawl areas layer")
        return
    }
    def gridCols = datasource.getColumnNames(grid_indicators)
    def lcz_columns_urban = ["LCZ_PRIMARY", "LCZ_WARM"]
    def lcz_columns = gridCols.intersect(lcz_columns_urban)
    if (lcz_columns.size()>0) {
        def outputTableName = postfix("sprawl_areas")
        if (distance == 0) {
            datasource.execute("""DROP TABLE IF EXISTS $outputTableName;
        create table $outputTableName as 
        select  CAST((row_number() over()) as Integer) as id, st_removeholes(the_geom) as the_geom from ST_EXPLODE('(
        select st_union(st_accum(the_geom)) as the_geom from
        $grid_indicators where lcz_warm>=2 
        and LCZ_PRIMARY NOT IN (101, 102,103,104,106, 107))')""".toString())
            return outputTableName
        } else {
            def tmp_sprawl = postfix("sprawl_tmp")
            datasource.execute("""
        DROP TABLE IF EXISTS  $tmp_sprawl, $outputTableName;
         create table $tmp_sprawl as 
        select  CAST((row_number() over()) as Integer) as id, st_removeholes(the_geom) as the_geom from ST_EXPLODE('(
        select st_union(st_accum(the_geom)) as the_geom from
        $grid_indicators where lcz_warm>=2 
        and LCZ_PRIMARY NOT IN (101, 102,103,104,106, 107))') 
        where st_isempty(st_buffer(the_geom, -100)) =false""".toString())
        datasource.execute("""CREATE TABLE $outputTableName as SELECT CAST((row_number() over()) as Integer) as id, 
         the_geom
        FROM
        ST_EXPLODE('(
        SELECT 
        st_removeholes(st_buffer(st_union(st_accum(st_buffer(st_removeholes(the_geom),$distance, ''quad_segs=2 endcap=flat
                     join=mitre mitre_limit=2''))),
                     -$distance, ''quad_segs=2 endcap=flat join=mitre mitre_limit=2'')) as the_geom  
         FROM ST_EXPLODE(''$tmp_sprawl'') )') ;
        DROP TABLE IF EXISTS $tmp_sprawl;
        """.toString())
            return outputTableName
        }
    }
    error("No LCZ_PRIMARY column to compute the sprawl areas layer")
    return
}

/**
 * This method is used to compute the difference between an input layer of polygons and the bounding box
 * of the input layer.
 * @param input_polygons a layer that contains polygons
 * @author Erwan Bocher (CNRS)
 */
String inversePolygonsLayer(JdbcDataSource datasource, String input_polygons) {
    def outputTableName = postfix("inverse_geometries")
    def tmp_extent = postfix("tmp_extent")
    datasource.execute("""DROP TABLE IF EXISTS $tmp_extent, $outputTableName;
    CREATE TABLE $tmp_extent as SELECT ST_EXTENT(THE_GEOM) as the_geom FROM $input_polygons;
    CREATE TABLE $outputTableName as
    SELECT CAST((row_number() over()) as Integer) as id, the_geom
    FROM
    ST_EXPLODE('(
        select st_difference(a.the_geom, st_accum(b.the_geom)) as the_geom from $tmp_extent as a, $input_polygons
        as b where st_dimension(b.the_geom)=2)');        
    DROP TABLE IF EXISTS $tmp_extent;
    """.toString())
    return outputTableName
}



/**
 * This methods allows to extract the cool area geometries inside polygons
 * A cool area is continous geometry defined by vegetation and water fractions.
 *
 * @author Erwan Bocher (CNRS)
 */
String extractCoolAreas(JdbcDataSource datasource, String grid_indicators,float distance = 100) {
    if (!grid_indicators) {
        error("No grid_indicators table to extract the cool areas layer")
        return
    }
    def gridCols = datasource.getColumnNames(grid_indicators)
    def lcz_columns_urban = ["LCZ_PRIMARY"]
    def lcz_columns = gridCols.intersect(lcz_columns_urban)

    if (lcz_columns.size()>0) {
        def outputTableName = postfix("cool_areas")
        datasource.execute("""
        DROP TABLE IF EXISTS $outputTableName;
        CREATE TABLE $outputTableName as SELECT CAST((row_number() over()) as Integer) as id,  the_geom FROM ST_EXPLODE('(
        SELECT ST_UNION(ST_ACCUM(a.THE_GEOM)) AS THE_GEOM FROM $grid_indicators as a
        where 
         a.LCZ_PRIMARY in (101, 102, 103,104, 106, 107))') ${distance>0?" where st_isempty(st_buffer(the_geom, -$distance)) =false":""};
        """.toString())
        return outputTableName
    }
    error("No LCZ_PRIMARY column to extract the cool areas")
    return
}