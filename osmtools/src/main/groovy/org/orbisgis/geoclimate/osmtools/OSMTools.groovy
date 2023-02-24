/*
 * Bundle OSMTools is part of the GeoClimate tool
 *
 * GeoClimate is a geospatial processing toolbox for environmental and climate studies .
 * GeoClimate is developed by the GIS group of the DECIDE team of the
 * Lab-STICC CNRS laboratory, see <http://www.lab-sticc.fr/>.
 *
 * The GIS group of the DECIDE team is located at :
 *
 * Laboratoire Lab-STICC – CNRS UMR 6285
 * Equipe DECIDE
 * UNIVERSITÉ DE BRETAGNE-SUD
 * Institut Universitaire de Technologie de Vannes
 * 8, Rue Montaigne - BP 561 56017 Vannes Cedex
 *
 * OSMTools is distributed under LGPL 3 license.
 *
 * Copyright (C) 2019-2021 CNRS (Lab-STICC UMR CNRS 6285)
 *
 *
 * OSMTools is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * OSMTools is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * OSMTools. If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, please consult: <https://github.com/orbisgis/geoclimate>
 * or contact directly:
 * info_at_ orbisgis.org
 */
package org.orbisgis.geoclimate.osmtools

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.orbisgis.geoclimate.osmtools.Loader as LOADER
import org.orbisgis.geoclimate.osmtools.Transform as TRANSFORM
import org.orbisgis.geoclimate.osmtools.utils.TransformUtils as TRANSFORM_UTILS
import org.orbisgis.geoclimate.osmtools.utils.Utilities as UTILITIES
import org.orbisgis.geoclimate.utils.AbstractScript
import org.slf4j.LoggerFactory

/**
 * Main script to access to all processes used to extract, transform and save OSM data as GIS layers.
 *
 * @author Erwan Bocher CNRS LAB-STICC
 * @author Elisabeth Le Saux UBS LAB-STICC
 * @author Sylvain PALOMINOS (UBS LAB-STICC 2019)
 */

abstract class OSMTools extends AbstractScript {
    def static Loader = new LOADER()
    def static Transform = new TRANSFORM()
    def static Utilities = new UTILITIES()
    def static TransformUtils = new TRANSFORM_UTILS()

    OSMTools() {
        super(LoggerFactory.getLogger(OSMTools.class))
        var context = (LoggerContext) LoggerFactory.getILoggerFactory()
        context.getLogger(OSMTools.class).setLevel(Level.INFO)
    }
}