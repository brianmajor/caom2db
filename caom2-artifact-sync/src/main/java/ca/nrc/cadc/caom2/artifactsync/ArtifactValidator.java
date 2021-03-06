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

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.caom2.Artifact;
import ca.nrc.cadc.caom2.ObservationResponse;
import ca.nrc.cadc.caom2.ObservationState;
import ca.nrc.cadc.caom2.Plane;
import ca.nrc.cadc.caom2.ReleaseType;
import ca.nrc.cadc.caom2.artifact.ArtifactMetadata;
import ca.nrc.cadc.caom2.artifact.ArtifactStore;
import ca.nrc.cadc.caom2.harvester.HarvestResource;
import ca.nrc.cadc.caom2.harvester.state.HarvestSkipURI;
import ca.nrc.cadc.caom2.harvester.state.HarvestSkipURIDAO;
import ca.nrc.cadc.caom2.persistence.ObservationDAO;
import ca.nrc.cadc.date.DateUtil;
import ca.nrc.cadc.net.HttpDownload;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.StringUtil;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.PrivilegedExceptionAction;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.apache.log4j.Logger;

/**
 * Class that compares artifacts in the caom2 metadata with the artifacts
 * in storage (via ArtifactStore).
 * 
 * @author majorb
 *
 */
public class ArtifactValidator implements PrivilegedExceptionAction<Object>, ShutdownListener  {
    
    public static final String STATE_CLASS = Artifact.class.getSimpleName();
    
    private ObservationDAO observationDAO;
    private HarvestSkipURIDAO harvestSkipURIDAO;
    private String source;
    private ArtifactStore artifactStore;
    private String collection;
    private boolean reportOnly;
    private URI caomTapResourceID;
    private URL caomTapURL;
    private boolean supportSkipURITable = false;
        
    private ExecutorService executor;
    
    private static final Logger log = Logger.getLogger(ArtifactValidator.class);

    public ArtifactValidator(DataSource dataSource, HarvestResource harvestResource, ObservationDAO observationDAO, 
            boolean reportOnly, ArtifactStore artifactStore) {
        this(harvestResource.getCollection(), reportOnly, artifactStore);
        this.observationDAO = observationDAO;
        this.source = harvestResource.getIdentifier();
        this.harvestSkipURIDAO = new HarvestSkipURIDAO(dataSource, harvestResource.getDatabase(), harvestResource.getSchema());
    }
    
    public ArtifactValidator(URI caomTapResourceID, 
            String collection, boolean reportOnly, ArtifactStore artifactStore) {
        this(collection, reportOnly, artifactStore);
        this.caomTapResourceID = caomTapResourceID;
    }
    
    public ArtifactValidator(URL caomTapURL, 
            String collection, boolean reportOnly, ArtifactStore artifactStore) {
        this(collection, reportOnly, artifactStore);
        this.caomTapURL = caomTapURL;
    }
    
    private ArtifactValidator(String collection, boolean reportOnly, ArtifactStore artifactStore) {
        this.collection = collection;
        this.reportOnly = reportOnly;
        this.artifactStore = artifactStore;
    }

    @Override
    public Object run() throws Exception {
        
        final long start = System.currentTimeMillis();
        log.info("Starting validation for collection " + collection);
        executor = Executors.newFixedThreadPool(2);
        final Future<TreeSet<ArtifactMetadata>> logicalQuery = executor.submit(new Callable<TreeSet<ArtifactMetadata>>() {
            public TreeSet<ArtifactMetadata> call() throws Exception {
                return getLogicalMetadata();
            }
        });
        log.debug("Submitted query to caom2");
        final Future<TreeSet<ArtifactMetadata>> physicalQuery = executor.submit(new Callable<TreeSet<ArtifactMetadata>>() {
            public TreeSet<ArtifactMetadata> call() throws Exception {
                return getPhysicalMetadata();
            }
        });
        log.debug("Submitted queries");
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.DAYS);
        log.debug("Queryies are complete");
        executor.shutdownNow();
        
        TreeSet<ArtifactMetadata> logicalArtifacts = logicalQuery.get();
        TreeSet<ArtifactMetadata> physicalArtifacts = physicalQuery.get();
        compareMetadata(logicalArtifacts, physicalArtifacts, start);
        return null;
    }
    
    void compareMetadata(TreeSet<ArtifactMetadata> logicalArtifacts, TreeSet<ArtifactMetadata> physicalArtifacts,
            long start) throws Exception {
        boolean supportSkipURITable = supportSkipURITable();
        long logicalCount = logicalArtifacts.size();
        long physicalCount = physicalArtifacts.size();
        log.debug("Found " + logicalCount + " logical artifacts.");
        log.debug("Found " + physicalCount + " physical artifacts.");
        long correct = 0;
        long diffLength = 0;
        long diffType = 0;
        long diffChecksum = 0;
        long notInLogical = 0;
        long notInPhysical = 0;
        long skipURICount = 0;
        long inSkipURICount = 0;
        
        DateFormat df = DateUtil.getDateFormat(DateUtil.IVOA_DATE_FORMAT, null);
        
        ArtifactMetadata nextLogical = null;
        
        for (ArtifactMetadata nextPhysical : physicalArtifacts) {
            
            String physicalLastModified = "null";
            if (nextPhysical.lastModified != null) {
                physicalLastModified = df.format(nextPhysical.lastModified);
            }
            if (logicalArtifacts.contains(nextPhysical)) {
                nextLogical = logicalArtifacts.ceiling(nextPhysical);
                String logicalicalLastModified = "null";
                if (nextLogical.lastModified != null) {
                    logicalicalLastModified = df.format(nextLogical.lastModified);
                }
                logicalArtifacts.remove(nextLogical);
                if (nextLogical.checksum != null && nextLogical.checksum.equals(nextPhysical.checksum)) {
                    // check content length
                    if (nextLogical.contentLength == null 
                            || !nextLogical.contentLength.equals(nextPhysical.contentLength)) {
                        diffLength++;
                        logJSON(new String[]
                            {"logType", "detail",
                             "anomaly", "diffLength",
                             "artifactURI", nextLogical.artifactURI,
                             "storageID", nextLogical.storageID,
                             "caomContentLength", nextLogical.contentLength,
                             "storageContentLength", nextPhysical.contentLength,
                             "caomCollection", collection,
                             "caomLastModified", logicalicalLastModified,
                             "ingestDate", physicalLastModified},
                            false);
                    } else if (nextLogical.contentType == null
                            || !nextLogical.contentType.equals(nextPhysical.contentType)) {
                        diffType++;
                        logJSON(new String[]
                            {"logType", "detail",
                             "anomaly", "diffType",
                             "artifactURI", nextLogical.artifactURI,
                             "storageID", nextLogical.storageID,
                             "caomContentType", nextLogical.contentType,
                             "storageContentType", nextPhysical.contentType,
                             "caomCollection", collection,
                             "caomLastModified", logicalicalLastModified,
                             "ingestDate", physicalLastModified},
                            false);
                    } else {
                        correct++;
                    }
                } else {
                    diffChecksum++;
                    if (supportSkipURITable && nextLogical.checksum != null && nextPhysical.checksum != null) {
                        if (checkAddToSkipTable(nextLogical)) {
                            skipURICount++;
                        } else {
                            inSkipURICount++;
                        }
                    }
                    logJSON(new String[]
                        {"logType", "detail",
                         "anomaly", "diffChecksum",
                         "artifactURI", nextLogical.artifactURI,
                         "storageID", nextLogical.storageID,
                         "caomChecksum", nextLogical.checksum,
                         "caomSize", nextLogical.contentLength,
                         "storageChecksum", nextPhysical.checksum,
                         "storageSize", nextPhysical.contentLength,
                         "caomCollection", collection,
                         "caomLastModified", logicalicalLastModified,
                         "ingestDate", physicalLastModified},
                        false);
                }
            } else {
                notInLogical++;
                logJSON(new String[]
                    {"logType", "detail",
                     "anomaly", "notInCAOM",
                     "storageID", nextPhysical.storageID,
                     "ingestDate", physicalLastModified},
                    false);
            }
        }
        
        // at this point, any artifact that is in logicalArtifacts, is not in physicalArtifacts
        notInPhysical += logicalArtifacts.size();
        for (ArtifactMetadata next : logicalArtifacts) {
            String lastModified = "null";
            if (next.lastModified != null) {
                lastModified = df.format(next.lastModified);
            }
            logJSON(new String[]
                {"logType", "detail",
                 "anomaly", "notInStorage",
                 "artifactURI", next.artifactURI,
                 "storageID", next.storageID,
                 "caomCollection", collection,
                 "caomLastModified", lastModified},
                false);
                
            // add to HavestSkipURI table if there is not already a row in the table
            if (supportSkipURITable) {
                if (checkAddToSkipTable(next)) {
                    skipURICount++;
                } else {
                    inSkipURICount++;
                }
            }
        }
        
        if (reportOnly) {
            // diff
            logJSON(new String[] {
                "logType", "summary",
                "collection", collection,
                "totalInCAOM", Long.toString(logicalCount),
                "totalInStorage", Long.toString(physicalCount),
                "totalCorrect", Long.toString(correct),
                "totalDiffChecksum", Long.toString(diffChecksum),
                "totalDiffLength", Long.toString(diffLength),
                "totalDiffType", Long.toString(diffType),
                "totalNotInCAOM", Long.toString(notInLogical),
                "totalNotInStorage", Long.toString(notInPhysical),
                "time", Long.toString(System.currentTimeMillis() - start)
                }, true);
        } else {
            // validate
            logJSON(new String[] {
                "logType", "summary",
                "collection", collection,
                "totalInCAOM", Long.toString(logicalCount),
                "totalInStorage", Long.toString(physicalCount),
                "totalCorrect", Long.toString(correct),
                "totalDiffChecksum", Long.toString(diffChecksum),
                "totalDiffLength", Long.toString(diffLength),
                "totalDiffType", Long.toString(diffType),
                "totalNotInCAOM", Long.toString(notInLogical),
                "totalNotInStorage", Long.toString(notInPhysical),
                "totalAlreadyInSkipURI", Long.toString(inSkipURICount),
                "totalNewSkipURI", Long.toString(skipURICount),
                "time", Long.toString(System.currentTimeMillis() - start)
                }, true);
        }
    }
    
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("Shutdown interruped");
                Thread.currentThread().interrupt();
            }
        }
    }
   
    private void logJSON(String[] data, boolean summaryInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean paired = true;
        for (String s : data) {
            sb.append("\"");
            sb.append(s);
            sb.append("\"");
            if (paired) {
                sb.append(":");
            } else {
                sb.append(",");
            }
            paired = !paired;
        }
        sb.setLength(sb.length() - 1);
        sb.append("}");
        if (summaryInfo || reportOnly) {
            System.out.println(sb.toString());
        }
    }

    private boolean supportSkipURITable() {
        return supportSkipURITable;
    }
    
    private boolean checkAddToSkipTable(ArtifactMetadata artifact) throws URISyntaxException {
        if (supportSkipURITable) {
            // add to HavestSkipURI table if there is not already a row in the table
            Date releaseDate = artifact.releaseDate;
            URI artifactURI = new URI(artifact.artifactURI);
            HarvestSkipURI skip = harvestSkipURIDAO.get(source, STATE_CLASS, artifactURI);
            if (skip == null && releaseDate != null) {
                if (!reportOnly) {
                    skip = new HarvestSkipURI(source, STATE_CLASS, artifactURI, releaseDate);
                    harvestSkipURIDAO.put(skip);
                    
                    // validate 
                    DateFormat df = DateUtil.getDateFormat(DateUtil.IVOA_DATE_FORMAT, null);
                    logJSON(new String[]
                        {"logType", "detail",
                         "action", "addedToSkipTable",
                         "artifactURI", artifact.artifactURI,
                         "caomCollection", artifact.collection,
                         "caomChecksum", artifact.checksum,
                         "caomLastModified", df.format(artifact.lastModified)},
                        true);
                }
                return true;
            }
            return false;
        }
        
        return false;
    }
    
    private TreeSet<ArtifactMetadata> getLogicalMetadata() throws Exception {
        TreeSet<ArtifactMetadata> result = new TreeSet<>(ArtifactMetadata.getComparator());
        if (StringUtil.hasText(source)) {
            // use database <server.database.schema>
            // HarvestSkipURI table is not supported in 'diff' mode, i.e. reportOnly = true
            this.supportSkipURITable = !reportOnly;
            long t1 = System.currentTimeMillis();
            List<ObservationState> states = observationDAO.getObservationList(collection, null, null, null);
            long t2 = System.currentTimeMillis();
            long dt = t2 - t1;
            log.info("get-state-list: " + states.size() + " " + dt + " ms");
            
            int depth = 3;
            ListIterator<ObservationState> iter = states.listIterator();
            t1 = System.currentTimeMillis();
            while (iter.hasNext()) {
                ObservationState s = iter.next();
                iter.remove(); // GC
                ObservationResponse resp = observationDAO.getAlt(s, depth);
                for (Plane plane : resp.observation.getPlanes()) {
                    for (Artifact artifact : plane.getArtifacts()) {
                        result.add(getMetadata(artifact, plane.dataRelease, plane.metaRelease));
                    }
                }
            }
            
            log.debug("Finished logical query in " + (System.currentTimeMillis() - t1) + " ms");
        } else {
            this.supportSkipURITable = false;
            if (caomTapResourceID != null) {
                // source is a TAP resource ID
                RegistryClient regClient = new RegistryClient();
                AuthMethod authMethod = AuthenticationUtil.getAuthMethodFromCredentials(AuthenticationUtil.getCurrentSubject());
                this.caomTapURL = regClient.getServiceURL(caomTapResourceID, Standards.TAP_10, authMethod, Standards.INTERFACE_UWS_SYNC);
            }
            
            // source is a TAP service URL or a TAP resource ID
            String adql = "select distinct(a.uri), a.lastModified, a.contentChecksum, a.contentLength, a.contentType "
                    + "from caom2.Artifact a "
                    + "join caom2.Plane p on a.planeID = p.planeID "
                    + "join caom2.Observation o on p.obsID = o.obsID "
                    + "where o.collection='" + collection + "'";

            log.debug("logical query: " + adql);
            long start = System.currentTimeMillis();
            result = query(caomTapURL, adql, true);
            log.debug("Finished logical query in " + (System.currentTimeMillis() - start) + " ms");
        }
        return result;
    }
    
    private ArtifactMetadata getMetadata(Artifact artifact, Date dataRelease, Date metaRelease) throws Exception {
        ArtifactMetadata metadata = new ArtifactMetadata(); 
        metadata.artifactURI = artifact.getURI().toASCIIString();
        metadata.checksum = getStorageChecksum(artifact.contentChecksum.toASCIIString());
        metadata.contentLength = Long.toString(artifact.contentLength);
        metadata.contentType = artifact.contentType;
        metadata.collection = collection;
        metadata.lastModified = artifact.getLastModified();
        metadata.storageID = artifactStore.toStorageID(artifact.getURI().toASCIIString());
        ReleaseType type = artifact.getReleaseType();
        if (ReleaseType.DATA.equals(type)) {
            metadata.releaseDate = dataRelease;
        } else if (ReleaseType.META.equals(type)) {
            metadata.releaseDate = metaRelease;
        } else {
            metadata.releaseDate = null;
        }
        
        return metadata;
    }
    
    private TreeSet<ArtifactMetadata> query(URL baseURL, String adql, boolean logical) throws Exception {
        StringBuilder queryString = new StringBuilder();
        queryString.append("LANG=ADQL&RESPONSEFORMAT=tsv&QUERY=");
        queryString.append(URLEncoder.encode(adql, "UTF-8"));
        URL url = new URL(baseURL.toString() + "?" + queryString.toString());
        ResultReader resultReader = new ResultReader(artifactStore, logical);
        HttpDownload get = new HttpDownload(url, resultReader);
        try {
            get.run();
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException(t);
        }
        if (get.getThrowable() != null) {
            if (get.getThrowable() instanceof Exception) {
                throw (Exception) get.getThrowable();
            } else {
                throw new RuntimeException(get.getThrowable());
            }
        }
        
        return resultReader.artifacts;
    }
    
    private String getStorageChecksum(String checksum) throws Exception {
        int colon = checksum.indexOf(":");
        return checksum.substring(colon + 1, checksum.length());
    }

    private TreeSet<ArtifactMetadata> getPhysicalMetadata() throws Exception {
        TreeSet<ArtifactMetadata> metadata = new TreeSet<ArtifactMetadata>(ArtifactMetadata.getComparator());
        metadata.addAll(artifactStore.list(collection));
        return metadata;
    }
}