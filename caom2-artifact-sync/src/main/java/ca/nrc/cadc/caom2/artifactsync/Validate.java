/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2018.                            (c) 2018.
*  Government of Canada                 Gouvernement du Canada
*  National Research Council            Conseil national de recherches
*  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
*  All rights reserved                  Tous droits réservés
*
*  NRC disclaims any warranties,        Le CNRC dénie toute garantie
*  expressed, implied, or               énoncée, implicite ou légale,
*  statutory, of any kind with          de quelque nature que ce
*  respect to the software,             soit, concernant le logiciel,
*  including without limitation         y compris sans restriction
*  any warranty of merchantability      toute garantie de valeur
*  or fitness for a particular          marchande ou de pertinence
*  purpose. NRC shall not be            pour un usage particulier.
*  liable in any event for any          Le CNRC ne pourra en aucun cas
*  damages, whether direct or           être tenu responsable de tout
*  indirect, special or general,        dommage, direct ou indirect,
*  consequential or incidental,         particulier ou général,
*  arising from the use of the          accessoire ou fortuit, résultant
*  software.  Neither the name          de l'utilisation du logiciel. Ni
*  of the National Research             le nom du Conseil National de
*  Council of Canada nor the            Recherches du Canada ni les noms
*  names of its contributors may        de ses  participants ne peuvent
*  be used to endorse or promote        être utilisés pour approuver ou
*  products derived from this           promouvoir les produits dérivés
*  software without specific prior      de ce logiciel sans autorisation
*  written permission.                  préalable et particulière
*                                       par écrit.
*
*  This file is part of the             Ce fichier fait partie du projet
*  OpenCADC project.                    OpenCADC.
*
*  OpenCADC is free software:           OpenCADC est un logiciel libre ;
*  you can redistribute it and/or       vous pouvez le redistribuer ou le
*  modify it under the terms of         modifier suivant les termes de
*  the GNU Affero General Public        la “GNU Affero General Public
*  License as published by the          License” telle que publiée
*  Free Software Foundation,            par la Free Software Foundation
*  either version 3 of the              : soit la version 3 de cette
*  License, or (at your option)         licence, soit (à votre gré)
*  any later version.                   toute version ultérieure.
*
*  OpenCADC is distributed in the       OpenCADC est distribué
*  hope that it will be useful,         dans l’espoir qu’il vous
*  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
*  without even the implied             GARANTIE : sans même la garantie
*  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
*  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
*  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
*  General Public License for           Générale Publique GNU Affero
*  more details.                        pour plus de détails.
*
*  You should have received             Vous devriez avoir reçu une
*  a copy of the GNU Affero             copie de la Licence Générale
*  General Public License along         Publique GNU Affero avec
*  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
*  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
*                                       <http://www.gnu.org/licenses/>.
*
*  $Revision: 5 $
*
************************************************************************
*/

package ca.nrc.cadc.caom2.artifactsync;

import ca.nrc.cadc.caom2.persistence.ObservationDAO;
import ca.nrc.cadc.util.ArgumentMap;
import ca.nrc.cadc.util.StringUtil;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;

/**
 * Class to support both 'validate' and 'diff' modes.
 *
 * @author majorb
 */
public class Validate extends Caom2ArtifactSync {

    private static Logger log = Logger.getLogger(Validate.class);
    protected ArtifactValidator validator = null;
    protected String collection = null;

    public Validate(ArgumentMap am) {
        super(am);

        if (!this.isDone) {
            // parent has not discovered any show stopper
            // arguments common to 'diff' and 'validategit' modes
            if (this.subject == null) {
                String msg = "Anonymous execution not supported.  Please use --netrc or --cert";
                this.printErrorUsage(msg);
            } else if (!am.isSet("collection")) {
                String msg = "Missing required parameter 'collection'";
                this.printErrorUsage(msg);
            } else {
                this.collection = this.parseCollection(am);
                
                if (!this.isDone) {
                    if (this.mode.equals("diff")) {
                        // diff mode
                        if (!am.isSet("source")) {
                            String msg = "Missing required parameter 'source'";
                            this.printErrorUsage(msg);
                        } else {
                            this.parseSourceParam(am);
                        }
                    } else {
                        // validate mode
                        if (!am.isSet("database")) {
                            String msg = "Missing required parameter 'database'";
                            this.printErrorUsage(msg);
                        } else {
                            this.parseDbParam(am, "database");
                            ObservationDAO observationDAO = new ObservationDAO();
                            observationDAO.setConfig(this.daoConfig);
        
                            this.validator = new Validator(observationDAO.getDataSource(),
                                    this.dbInfo, observationDAO, this.collection, false, this.artifactStore);
                        }
                    }
                }
            }
        }
    }
    
    public void execute() throws Exception {
        if (!this.isDone) {
            this.setExitValue(2);
            List<ShutdownListener> listeners = new ArrayList<ShutdownListener>(2);
            listeners.add(validator);
            Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownHook(listeners)));
            Subject.doAs(this.subject, validator);
            this.setExitValue(0); // finished cleanly
        }
    }

    public void printUsage() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nusage: ").append(this.applicationName).append(" [mode-args]");
        sb.append("\n\n    [mode-args]:");
        if (this.mode.equals("diff")) {
            sb.append("\n        --source=<server.database.schema | TAP resource ID | TAP Service URL>");
            sb.append("\n        --collection=<collection> : The collection to determine the artifacts differences");
        } else {
            sb.append("\n        --database=<server.database.schema>");
            sb.append("\n        --collection=<collection> : The collection to validate");
        }
        sb.append("\n\n    optional general args:");
        sb.append("\n        -v | --verbose");
        sb.append("\n        -d | --debug");
        sb.append("\n        -h | --help");
        sb.append("\n        --profile : Profile task execution");
        sb.append("\n\n    authentication:");
        sb.append("\n        [--netrc|--cert=<pem file>]");
        sb.append("\n        --netrc : read username and password(s) from ~/.netrc file");
        sb.append("\n        --cert=<pem file> : read client certificate from PEM file");

        log.warn(sb.toString());    
        this.setIsDone(true);
    }
    
    private void parseSourceParam(ArgumentMap am) {
        String source = am.getValue("source");
        if (!StringUtil.hasText(source)) {
            String msg = "Must specify source." ;
            this.printErrorUsage(msg);
        } else if (source.equalsIgnoreCase("true")) {
            String msg = "Must specify source with source=";
            this.printErrorUsage(msg);
        } else if (source.contains("ivo:")) {
            // source points to a TAP Resource ID
            URI tapResourceID = URI.create(source);
            this.validator = new Validator(tapResourceID, collection, true, artifactStore);
        } else if (source.contains("http:")) {
            // source points to a TAP Service URL
            URL tapServiceURL;
            try {
                tapServiceURL = new URL(source);
                this.validator = new Validator(tapServiceURL, collection, true, artifactStore);
            } catch (MalformedURLException e) {
                String msg = "Must specify source." ;
                this.logException(msg, e);
            }
        } else {
            // source points to a database
            this.parseDbParam(am, "source");
            ObservationDAO observationDAO = new ObservationDAO();
            observationDAO.setConfig(this.daoConfig);
            
            this.validator = new Validator(observationDAO.getDataSource(),
                    this.dbInfo, observationDAO, this.collection, true, this.artifactStore);
        }
    }
}
