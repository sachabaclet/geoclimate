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
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.operation.distance.IndexedFacetDistance
import org.orbisgis.data.jdbc.JdbcDataSource
import org.orbisgis.geoclimate.Geoindicators

@BaseScript Geoindicators geoindicators


/**
 * Disaggregate a set of population values to a grid
 * Update the input grid table to add new population columns
 * @param inputGridTableName the building table
 * @param inputPopulation the spatial unit that contains the population to distribute
 * @param inputPopulationColumns the list of the columns to disaggregate
 * @return the input Grid table with the new population columns
 *
 * @author Erwan Bocher, CNRS
 */
String gridPopulation(JdbcDataSource datasource, String gridTable, String populationTable, List populationColumns = []) {
    def BASE_NAME = "grid_with_population"
    def ID_RSU = "id_grid"
    def ID_POP = "id_pop"

    debug "Computing grid population"

    // The name of the outputTableName is constructed
    def outputTableName = postfix BASE_NAME

    //Indexing table
    datasource.createSpatialIndex(gridTable, "the_geom")
    datasource.createSpatialIndex(populationTable, "the_geom")
    def popColumns = []
    def sum_popColumns = []
    if (populationColumns) {
        datasource."$populationTable".getColumns().each { col ->
            if (!["the_geom", "id_pop"].contains(col.toLowerCase()
            ) && populationColumns.contains(col.toLowerCase())) {
                popColumns << "b.$col"
                sum_popColumns << "sum((a.area_rsu * $col)/b.sum_area_rsu) as $col"
            }
        }
    } else {
        warn "Please set a list one column that contain population data to be disaggregated"
        return
    }

    //Filtering the grid to get only the geometries that intersect the population table
    def gridTable_pop = postfix gridTable
    datasource.execute("""
                drop table if exists $gridTable_pop;
                CREATE TABLE $gridTable_pop AS SELECT (ST_AREA(ST_INTERSECTION(a.the_geom, st_force2D(b.the_geom))))  as area_rsu, a.$ID_RSU, 
                b.id_pop, ${popColumns.join(",")} from
                $gridTable as a, $populationTable as b where a.the_geom && b.the_geom and
                st_intersects(a.the_geom, b.the_geom);
                create index on $gridTable_pop ($ID_RSU);
                create index on $gridTable_pop ($ID_POP);
            """.toString())

    def gridTable_pop_sum = postfix "grid_pop_sum"
    def gridTable_area_sum = postfix "grid_area_sum"
    //Aggregate population values
    datasource.execute("""drop table if exists $gridTable_pop_sum, $gridTable_area_sum;
            create table $gridTable_area_sum as select id_pop, sum(area_rsu) as sum_area_rsu
            from $gridTable_pop group by $ID_POP;
            create index on $gridTable_area_sum($ID_POP);
            create table $gridTable_pop_sum 
            as select a.$ID_RSU, ${sum_popColumns.join(",")} 
            from $gridTable_pop as a, $gridTable_area_sum as b where a.$ID_POP=b.$ID_POP group by $ID_RSU;
            CREATE INDEX ON $gridTable_pop_sum ($ID_RSU);
            DROP TABLE IF EXISTS $outputTableName;
            CREATE TABLE $outputTableName AS SELECT a.*, ${popColumns.join(",")} from $gridTable a  
            LEFT JOIN $gridTable_pop_sum  b on a.$ID_RSU=b.$ID_RSU;
            drop table if exists $gridTable_pop,$gridTable_pop_sum, $gridTable_area_sum ;""".toString())

    return outputTableName
}


/**
 * Create a multi-scale grid and aggregate the LCZ_PRIMARY indicators for each level of the grid.
 * For each level, the adjacent cells are preserved as well as the number of urban and natural cells.
 * To distinguish between LCZ cells with the same count per level, a weight is used to select only one LCZ type
 * corresponding to their potential impact on heat
 *
 * @param datasource connection to the database
 * @param grid_indicators a grid that contains for each cell the LCZ_PRIMARY
 * @param id_grid grid cell column identifier
 * @param nb_levels number of aggregate levels. Default is 1
 *
 * @return a the initial grid with all aggregated values by levels and the indexes (row, col) for each levels
 * @author Erwan Bocher (CNRS)
 */
String multiscaleLCZGrid(JdbcDataSource datasource, String grid_indicators, String id_grid, int nb_levels = 1) {
    if (!grid_indicators) {
        error("No grid_indicators table to aggregate the LCZ values")
        return
    }
    if (nb_levels <= 0 || nb_levels >= 10) {
        error("The number of levels to aggregate the LCZ values must be between 1 and 10")
        return
    }

    def gridColumns = datasource.getColumnNames(grid_indicators)

    if(gridColumns.intersect(["LCZ_PRIMARY", "ID_ROW", "ID_COLUMN", id_grid]).size()==0){
        error("The grid indicators table must contain the columns LCZ_PRIMARY, ID_ROW, $id_grid")
        return
    }

    datasource.execute("""create index on $grid_indicators(id_row,id_col)""".toString())
    ///First build the index levels for each cell of the input grid
    def grid_scaling_indices = postfix("grid_scaling_indices")
    def grid_levels_query = []
    int grid_offset = 3
    int offsetCol = 1
    for (int i in 1..nb_levels) {
        int level = Math.pow(grid_offset, i)
        grid_levels_query << " (CAST (ABS(ID_ROW-1)/${level}  AS INT)+1) AS ID_ROW_LOD_$i," +
                "(CAST (ABS(ID_COL-1)/${level} AS INT)+$offsetCol-1) AS ID_COL_LOD_$i"
        offsetCol++
    }

    //Compute the indices for each levels and find the 8 adjacent cells
    datasource.execute("""DROP TABLE IF EXISTS $grid_scaling_indices;
    CREATE TABLE $grid_scaling_indices as SELECT *, ${grid_levels_query.join(",")},
    (SELECT LCZ_PRIMARY FROM $grid_indicators WHERE ID_ROW = a.ID_ROW+1 AND ID_COL=a.ID_COL) AS LCZ_PRIMARY_N,
    (SELECT LCZ_PRIMARY FROM $grid_indicators WHERE ID_ROW = a.ID_ROW+1 AND ID_COL=a.ID_COL+1) AS LCZ_PRIMARY_NE,
    (SELECT LCZ_PRIMARY FROM $grid_indicators WHERE ID_ROW = a.ID_ROW AND ID_COL=a.ID_COL+1) AS LCZ_PRIMARY_E,
    (SELECT LCZ_PRIMARY FROM $grid_indicators WHERE ID_ROW = a.ID_ROW-1 AND ID_COL=a.ID_COL+1) AS LCZ_PRIMARY_SE,
    (SELECT LCZ_PRIMARY FROM $grid_indicators WHERE ID_ROW = a.ID_ROW-1 AND ID_COL=a.ID_COL) AS LCZ_PRIMARY_S,
    (SELECT LCZ_PRIMARY FROM $grid_indicators WHERE ID_ROW = a.ID_ROW-1 AND ID_COL=a.ID_COL-1) AS LCZ_PRIMARY_SW,
    (SELECT LCZ_PRIMARY FROM $grid_indicators WHERE ID_ROW = a.ID_ROW AND ID_COL=a.ID_COL-1) AS LCZ_PRIMARY_W,
    (SELECT LCZ_PRIMARY FROM $grid_indicators WHERE ID_ROW = a.ID_ROW+1 AND ID_COL=a.ID_COL-1) AS LCZ_PRIMARY_NW
    FROM 
    $grid_indicators as a;  """.toString())
    //Add LCZ_WARM count at this first level
    def lcz_warm_first_level = postfix("lcz_warm_first_level")
    datasource.execute("""DROP TABLE IF EXISTS $lcz_warm_first_level;
    CREATE TABLE $lcz_warm_first_level as SELECT *,
    (CASE WHEN LCZ_PRIMARY_N in (1,2,3,4,5,6,7,8,9,10,105) THEN 1 ELSE 0 END +
    CASE WHEN  LCZ_PRIMARY_NE in (1,2,3,4,5,6,7,8,9,10,105) THEN 1 ELSE 0 END +
    CASE WHEN  LCZ_PRIMARY_E in (1,2,3,4,5,6,7,8,9,10,105) THEN 1 ELSE 0 END +
    CASE WHEN  LCZ_PRIMARY_SE in (1,2,3,4,5,6,7,8,9,10,105) THEN 1 ELSE 0 END +
    CASE WHEN  LCZ_PRIMARY_S in (1,2,3,4,5,6,7,8,9,10,105) THEN 1 ELSE 0 END +
    CASE WHEN  LCZ_PRIMARY_SW in (1,2,3,4,5,6,7,8,9,10,105) THEN 1 ELSE 0 END +
    CASE WHEN  LCZ_PRIMARY_W in (1,2,3,4,5,6,7,8,9,10,105) THEN 1 ELSE 0 END +
    CASE WHEN  LCZ_PRIMARY_NW in (1,2,3,4,5,6,7,8,9,10,105) THEN 1 ELSE 0 END+
    CASE WHEN  LCZ_PRIMARY in (1,2,3,4,5,6,7,8,9,10,105) THEN 1 ELSE 0 END)  AS LCZ_WARM 
    FROM $grid_scaling_indices """.toString())

    def tablesToDrop = []
    def tableLevelToJoin = lcz_warm_first_level
    tablesToDrop << grid_scaling_indices
    tablesToDrop << lcz_warm_first_level

    //Process all level of details
    for (int i in 1..nb_levels) {
        //Index the level row and col
        datasource.execute("""
        CREATE INDEX IF NOT EXISTS ${grid_scaling_indices}_idx ON $grid_scaling_indices(id_row_lod_${i},id_col_lod_${i})""".toString())
        //First compute the number of cells by level of detail
        //Use the original grid to aggregate the data
        //A weight is used to select the LCZ value when the mode returns more than one possibility
        def lcz_count_lod = postfix("lcz_count_lod")
        tablesToDrop << lcz_count_lod
        datasource.execute(""" 
        DROP TABLE IF EXISTS $lcz_count_lod;
        CREATE TABLE $lcz_count_lod as
        SELECT  COUNT(*) FILTER (WHERE LCZ_PRIMARY IS NOT NULL) AS COUNT, LCZ_PRIMARY,
        ID_ROW_LOD_${i}, ID_COL_LOD_${i},
        CASE WHEN LCZ_PRIMARY=105 THEN 11
        WHEN LCZ_PRIMARY=107 THEN 12
        WHEN LCZ_PRIMARY=106 THEN 13
        WHEN LCZ_PRIMARY= 101 THEN 14
        WHEN LCZ_PRIMARY =102 THEN 15
        WHEN LCZ_PRIMARY IN (103,104) THEN 16
        ELSE LCZ_PRIMARY END AS weight_lcz,
        FROM $grid_scaling_indices 
        WHERE LCZ_PRIMARY IS NOT NULL 
        GROUP BY ID_ROW_LOD_${i}, ID_COL_LOD_${i}, LCZ_PRIMARY;""".toString())

        //Select the LCZ values according the maximum number of cells and the weight
        //Note that we compute the number of cells for urban and cool LCZ
        def lcz_count_lod_mode = postfix("lcz_count_lod_mode")
        tablesToDrop << lcz_count_lod_mode
        datasource.execute(""" 
        CREATE INDEX ON $lcz_count_lod(ID_ROW_LOD_${i}, ID_COL_LOD_${i});
        DROP TABLE IF EXISTS  $lcz_count_lod_mode;    
        CREATE TABLE $lcz_count_lod_mode as
        select distinct on (ID_ROW_LOD_${i}, ID_COL_LOD_${i}) *,
        (select sum(count) from  $lcz_count_lod where   
        LCZ_PRIMARY in (1,2,3,4,5,6,7,8,9,10,105) 
        and ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i} and ID_COL_LOD_${i}= a.ID_COL_LOD_${i}) AS  LCZ_WARM_LOD_${i},
        (select sum(count) from  $lcz_count_lod where  
        LCZ_PRIMARY in (101,102,103,104,106,107) 
        and ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i} and ID_COL_LOD_${i}= a.ID_COL_LOD_${i}) AS  LCZ_COOL_LOD_${i},
        from $lcz_count_lod as a
        order by count desc, ID_ROW_LOD_${i}, ID_COL_LOD_${i}, weight_lcz;""".toString())

        //Find the 8 adjacent cells for the current level
        def grid_lod_level_final = postfix("grid_lod_level_final")
        tablesToDrop << grid_lod_level_final
        datasource.execute("""
        CREATE INDEX on $lcz_count_lod_mode(ID_ROW_LOD_${i}, ID_COL_LOD_${i});
        DROP TABLE IF EXISTS $grid_lod_level_final;
        CREATE TABLE $grid_lod_level_final as select * EXCEPT(LCZ_PRIMARY, COUNT, weight_lcz), LCZ_PRIMARY AS LCZ_PRIMARY_LOD_${i},
        (SELECT LCZ_PRIMARY FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}+1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}) AS LCZ_PRIMARY_N_LOD_${i},
        (SELECT LCZ_PRIMARY FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}+1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}+1) AS LCZ_PRIMARY_NE_LOD_${i},
        (SELECT LCZ_PRIMARY FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i} AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}+1) AS LCZ_PRIMARY_E_LOD_${i},
        (SELECT LCZ_PRIMARY FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}-1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}+1) AS LCZ_PRIMARY_SE_LOD_${i},
        (SELECT LCZ_PRIMARY FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}-1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}) AS LCZ_PRIMARY_S_LOD_${i},
        (SELECT LCZ_PRIMARY FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}-1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}-1) AS LCZ_PRIMARY_SW_LOD_${i},
        (SELECT LCZ_PRIMARY FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i} AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}-1) AS LCZ_PRIMARY_W_LOD_${i},
        (SELECT LCZ_PRIMARY FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}+1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}-1) AS LCZ_PRIMARY_NW_LOD_${i},
     
        (SELECT LCZ_WARM_LOD_${i} FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}+1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}) AS LCZ_WARM_N_LOD_${i},
        (SELECT LCZ_WARM_LOD_${i} FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}+1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}+1) AS LCZ_WARM_NE_LOD_${i},
        (SELECT LCZ_WARM_LOD_${i} FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i} AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}+1) AS LCZ_WARM_E_LOD_${i},
        (SELECT LCZ_WARM_LOD_${i} FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}-1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}+1) AS LCZ_WARM_SE_LOD_${i},
        (SELECT LCZ_WARM_LOD_${i} FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}-1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}) AS LCZ_WARM_S_LOD_${i},
        (SELECT LCZ_WARM_LOD_${i} FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}-1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}-1) AS LCZ_WARM_SW_LOD_${i},
        (SELECT LCZ_WARM_LOD_${i} FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i} AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}-1) AS LCZ_WARM_W_LOD_${i},
        (SELECT LCZ_WARM_LOD_${i} FROM $lcz_count_lod_mode WHERE ID_ROW_LOD_${i} = a.ID_ROW_LOD_${i}+1 AND ID_COL_LOD_${i}=a.ID_COL_LOD_${i}-1) AS LCZ_WARM_NW_LOD_${i},
     
         FROM  $lcz_count_lod_mode as a;  """.toString())

        tableLevelToJoin << grid_lod_level_final

        //Join the final grid level with the original grid
        def grid_level_join = postfix("grid_level_join")
        datasource.execute("""
        CREATE INDEX IF NOT EXISTS ${tableLevelToJoin}_idx ON $tableLevelToJoin (ID_ROW_LOD_${i}, ID_COL_LOD_${i});
        create index on $grid_lod_level_final(ID_ROW_LOD_${i}, ID_COL_LOD_${i});
        DROP TABLE IF EXISTS $grid_level_join;
        CREATE TABLE $grid_level_join as 
        select a.* EXCEPT(ID_ROW_LOD_${i}, ID_COL_LOD_${i}),  
        b.* from $tableLevelToJoin as a,  $grid_lod_level_final as b 
        where a.ID_ROW_LOD_${i} = b.ID_ROW_LOD_${i} and a.ID_COL_LOD_${i}= b.ID_COL_LOD_${i}
        group by a.ID_ROW_LOD_${i}, a.ID_COL_LOD_${i} , a.id_grid;
        """.toString())
        tableLevelToJoin = grid_level_join
    }
    datasource.dropTable(tablesToDrop)
    return tableLevelToJoin

}


/**
 * Compute the distance from each grid cell to the edge of a polygon
 *
 * @param datasource a connection to the database
 * @param input_polygons a set of polygons
 * @param grid a regular grid
 * @param id_grid name of the unique identifier column for the cells of the grid
 * @author Erwan Bocher (CNRS)
 * TODO : convert this method as a function table in H2GIS
 */
String gridDistances(JdbcDataSource datasource, String input_polygons, String grid, String id_grid) {
    if (!input_polygons) {
        error("The input polygons cannot be null or empty")
        return
    }
    if (!grid) {
        error("The grid cannot be null or empty")
        return
    }
    if (!id_grid) {
        error("Please set the column name identifier for the grid cells")
        return
    }
    int epsg = datasource.getSrid(grid)
    def outputTableName = postfix("grid_distances")

    datasource.execute(""" DROP TABLE IF EXISTS $outputTableName;
        CREATE TABLE $outputTableName (THE_GEOM GEOMETRY,ID INT, DISTANCE FLOAT);
        """.toString())

    datasource.createSpatialIndex(input_polygons)
    datasource.createSpatialIndex(grid)

    datasource.withBatch(100) { stmt ->
        datasource.eachRow("SELECT the_geom from $input_polygons".toString()) { row ->
            Geometry geom = row.the_geom
            if (geom) {
                IndexedFacetDistance indexedFacetDistance = new IndexedFacetDistance(geom)
                datasource.eachRow("""SELECT the_geom, ${id_grid} as id from $grid 
                where ST_GEOMFROMTEXT('${geom}',$epsg)  && the_geom and 
            st_intersects(ST_GEOMFROMTEXT('${geom}',$epsg) , ST_POINTONSURFACE(the_geom))""".toString()) { cell ->
                    Geometry cell_geom = cell.the_geom
                    double distance = indexedFacetDistance.distance(cell_geom.getCentroid())
                    stmt.addBatch "insert into $outputTableName values(ST_GEOMFROMTEXT('${cell_geom}',$epsg), ${cell.id},${distance})".toString()
                }
            }
        }
    }
    return outputTableName
}
