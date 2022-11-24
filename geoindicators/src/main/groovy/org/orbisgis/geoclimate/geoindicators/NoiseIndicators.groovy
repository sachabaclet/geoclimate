package org.orbisgis.geoclimate.geoindicators

import groovy.transform.BaseScript
import org.locationtech.jts.geom.Geometry
import org.orbisgis.data.H2GIS
import org.orbisgis.geoclimate.Geoindicators
import org.orbisgis.process.api.IProcess

@BaseScript Geoindicators geoindicators


/**
 * This process computes a ground acoustic table.
 *
 * The ground table acoustic is based on a set of layers : building, road, water, vegetation  water and impervious.
 *
 * All layers are merged to find the smallest common geometry.
 *
 * The g coefficient is defined according the type of the surface.
 *
 * The type of surface is retrieved from the input layers.
 *
 * An array defined the priority order to set between layers in order to remove potential double count
 * of overlapped layers.
 *
 * The building and road geometries are not retained in the ground acoustic table.
 *
 *
 * The schema of the ground acoustic table should be defined with 5 columns :
 *
 * id_ground : unique identifier
 * the_geom : polygon or multipolygon
 * g : acoustic absorption coefficient
 * type : the type of the surface
 * layer : the layer selected to identify the surface
 *
 * See https://hal.archives-ouvertes.fr/hal-00985998/document
 * @return
 */
IProcess groundAcousticAbsorption() {
    return create {
        title "Compute a ground acoustic absorption table"
        inputs zone: String, id_zone: String, building: "", road: "", water: "", vegetation: "",
                impervious: "", datasource: H2GIS, jsonFilename: ""
        outputs ground_acoustic: String
        run { zone, id_zone, building, road, water, vegetation, impervious, H2GIS datasource, jsonFilename ->
            def outputTableName = postfix("GROUND_ACOUSTIC")
            datasource.execute """ drop table if exists $outputTableName;
                CREATE TABLE $outputTableName (THE_GEOM GEOMETRY, id_ground serial,G float, type VARCHAR, layer VARCHAR);""".toString()

            def paramsDefaultFile = this.class.getResourceAsStream("ground_acoustic_absorption.json")
            def absorption_params = Geoindicators.DataUtils.parametersMapping(jsonFilename, paramsDefaultFile)
            def default_absorption = absorption_params.default_g
            def g_absorption = absorption_params.g
            def layer_priorities = absorption_params.layer_priorities

            IProcess process = Geoindicators.RsuIndicators.groundLayer()
            if (process.execute(["zone"    : zone, "id_zone": id_zone,
                                 "building": building, "road": road, "vegetation": vegetation,
                                 "water"   : water, "impervious": impervious, datasource: datasource, priorities: layer_priorities])) {
                def ground = process.results.ground
                int rowcount = 1
                datasource.withBatch(100) { stmt ->
                    datasource.eachRow("SELECT the_geom, TYPE, layer FROM $ground where layer NOT in ('building' ,'road')".toString()) { row ->
                        def type = row.type
                        def layer = row.layer
                        float g_coeff
                        //There is no type
                        if (!type) {
                            g_coeff = default_absorption as float
                        } else {
                            g_coeff = g_absorption.get(type) as float
                        }
                        Geometry geom = row.the_geom
                        def epsg = geom.getSRID()
                        stmt.addBatch "insert into $outputTableName values(ST_GEOMFROMTEXT('${geom}',$epsg), ${rowcount++},${g_coeff}, '${type}', '${layer}')".toString()
                    }
                }
            }
            debug('Ground acoustic transformation finishes')
            [ground_acoustic: outputTableName]
        }

    }
}

