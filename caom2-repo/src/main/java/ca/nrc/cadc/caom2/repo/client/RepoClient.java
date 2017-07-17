/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2011.                            (c) 2011.
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

package ca.nrc.cadc.caom2.repo.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.AccessControlException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.caom2.Observation;
import ca.nrc.cadc.caom2.ObservationState;
import ca.nrc.cadc.caom2.ObservationURI;
import ca.nrc.cadc.date.DateUtil;
import ca.nrc.cadc.net.HttpDownload;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;

public class RepoClient
{

    private static final Logger log = Logger.getLogger(RepoClient.class);
    private static final URI standardID = Standards.CAOM2REPO_OBS_23;
    private static final Integer MAX_NUMBER = 300;

    private final DateFormat df = DateUtil.getDateFormat(DateUtil.IVOA_DATE_FORMAT, DateUtil.UTC);

    private URI resourceID = null;
    private URL baseServiceURL = null;

    private int nthreads = 1;
    private Comparator<ObservationState> maxLasModifiedComparator = new Comparator<ObservationState>()
    {
        @Override
        public int compare(ObservationState o1, ObservationState o2)
        {
            return o1.maxLastModified.compareTo(o2.maxLastModified);
        }
    };
    private Comparator<ObservationState> uriComparator = new Comparator<ObservationState>()
    {
        @Override
        public int compare(ObservationState o1, ObservationState o2)
        {
            return o1.getURI().compareTo(o2.getURI());
        }
    };

    /**
     * Create new CAOM RepoClient.
     *
     * @param resourceID
     *            the service identifier
     * @param nthreads
     *            number of threads to use when getting list of observations
     */
    public RepoClient(URI resourceID, int nthreads)
    {
        this.nthreads = nthreads;
        this.resourceID = resourceID;
    }

    private void init()
    {
        RegistryClient rc = new RegistryClient();

        Subject s = AuthenticationUtil.getCurrentSubject();
        AuthMethod meth = AuthenticationUtil.getAuthMethodFromCredentials(s);
        if (meth == null)
            meth = AuthMethod.ANON;
        this.baseServiceURL = rc.getServiceURL(this.resourceID, standardID, meth);
        if (baseServiceURL == null)
            throw new RuntimeException("not found: " + resourceID + " + " + standardID + " + " + meth);

        log.debug("service URL: " + baseServiceURL.toString());
        log.debug("AuthMethod:  " + meth);
    }

    public List<ObservationState> getObservationList(String collection, Date start, Date end, Integer maxrec) throws AccessControlException
    {
        init();

        List<ObservationState> accList = new ArrayList<ObservationState>();
        List<ObservationState> partialList = null;
        boolean tooBigRequest = maxrec == null || maxrec > MAX_NUMBER;

        Integer rec = maxrec;
        Integer recCounter = 0;
        if (tooBigRequest)
        {
            rec = MAX_NUMBER;
        }
        // Use HttpDownload to make the http GET calls (because it handles a lot
        // of the
        // authentication stuff)
        boolean go = true;
        String surlCommon = baseServiceURL.toExternalForm() + File.separator + collection;

        while (go)
        {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!tooBigRequest)
                go = false;// only one go
            String surl = surlCommon;
            if (rec != null)
                surl = surl + "?maxRec=" + (rec + 1);
            if (start != null)
                surl = surl + "&start=" + df.format(start);
            if (end != null)
                surl = surl + "&end=" + df.format(end);
            URL url;
            log.info("URL: " + surl);
            try
            {
                url = new URL(surl);
                HttpDownload get = new HttpDownload(url, bos);

                get.run();
                if (get.getThrowable() != null)
                {
                    if (get.getThrowable() instanceof AccessControlException)
                        throw (AccessControlException) get.getThrowable();
                    throw new RuntimeException("failed to get observation list", get.getThrowable());
                }
            }
            catch (MalformedURLException e)
            {
                throw new RuntimeException("BUG: failed to generate observation list url", e);
            }

            try
            {
                partialList = transformByteArrayOutputStreamIntoListOfObservationState(bos, df, '\t', '\n');
                if (partialList != null && accList != null && !partialList.isEmpty() && !accList.isEmpty() && accList.get(accList.size() - 1).equals(partialList.get(0)))
                {
                    partialList.remove(0);
                }

                accList.addAll(partialList);
                bos.close();
            }
            catch (ParseException | IOException e)
            {
                throw new RuntimeException("Unable to list of ObservationState from " + bos.toString(), e);
            }

            start = accList.get(accList.size() - 1).maxLastModified;
            recCounter = accList.size();
            if (maxrec != null && maxrec - recCounter < rec)
            {
                rec = maxrec - recCounter;
            }
            log.info("dinamic batch (rec): " + rec);

            if (accList.isEmpty() || (maxrec != null && recCounter >= maxrec) || (start != null && end != null && start.equals(end)) || partialList.size() < rec)
            {
                go = false;
            }
        }
        return accList;
    }

    public Iterator<Observation> observationIterator()
    {
        return null;

    }

    public void setConfig(Map<String, Object> config1)
    {

    }

    public List<WorkerResponse> getList(String collection, Date startDate, Date end, Integer numberOfObservations) throws InterruptedException, ExecutionException
    {
        init();

        // startDate = null;
        // end = df.parse("2017-06-20T09:03:15.360");
        List<WorkerResponse> list = new ArrayList<WorkerResponse>();

        List<ObservationState> stateList = getObservationList(collection, startDate, end, numberOfObservations);

        // Create tasks for each file
        List<Callable<WorkerResponse>> tasks = new ArrayList<Callable<WorkerResponse>>();

        // the current subject usually gets propagated into a thread pool, but
        // gets attached
        // when the thread is created so we explicitly pass it it and do another
        // Subject.doAs in
        // case
        // thread pool management is changed
        Subject subjectForWorkerThread = AuthenticationUtil.getCurrentSubject();
        for (ObservationState os : stateList)
        {
            tasks.add(new Worker(os, subjectForWorkerThread, baseServiceURL.toExternalForm()));
        }

        ExecutorService taskExecutor = null;
        try
        {
            // Run tasks in a fixed thread pool
            taskExecutor = Executors.newFixedThreadPool(nthreads);
            List<Future<WorkerResponse>> futures;

            futures = taskExecutor.invokeAll(tasks);

            for (Future<WorkerResponse> f : futures)
            {
                WorkerResponse res = null;
                res = f.get();

                if (f.isDone())
                {
                    list.add(res);
                }
            }
        }
        catch (InterruptedException | ExecutionException e)
        {
            log.error("Error when executing thread in ThreadPool: " + e.getMessage() + " caused by: " + e.getCause().toString());
            throw e;
        }
        finally
        {
            if (taskExecutor != null)
            {
                taskExecutor.shutdown();
            }
        }

        return list;
    }

    public WorkerResponse get(ObservationURI uri)
    {
        init();
        if (uri == null)
            throw new IllegalArgumentException("uri cannot be null");

        ObservationState os = new ObservationState(uri);

        // see comment above in getList
        Subject subjectForWorkerThread = AuthenticationUtil.getCurrentSubject();
        Worker wt = new Worker(os, subjectForWorkerThread, baseServiceURL.toExternalForm());
        return wt.getObservation();
    }

    public WorkerResponse get(String collection, URI uri, Date start)
    {
        if (uri == null)
            throw new IllegalArgumentException("uri cannot be null");

        init();

        log.info("******************* getObservationList(collection, start, null, null) " + collection);

        List<ObservationState> list = getObservationList(collection, start, null, null);
        ObservationState obsState = null;
        for (ObservationState os : list)
        {
            if (!os.getURI().getURI().equals(uri))
            {
                continue;
            }
            obsState = os;
            break;
        }

        log.info("******************* getting to getList " + obsState);

        if (obsState != null)
        {
            // see comment above in getList
            Subject subjectForWorkerThread = AuthenticationUtil.getCurrentSubject();
            Worker wt = new Worker(obsState, subjectForWorkerThread, baseServiceURL.toExternalForm());
            return wt.getObservation();
        }
        else
        {
            return null;
        }
    }

    private List<ObservationState> transformByteArrayOutputStreamIntoListOfObservationState(final ByteArrayOutputStream bos, DateFormat sdf, char separator, char endOfLine)

            throws ParseException, IOException
    {
        init();

        List<ObservationState> list = new ArrayList<ObservationState>();

        String id = null;
        String sdate = null;
        String collection = null;

        String aux = "";
        boolean readingCollection = true;
        boolean readingId = false;

        for (int i = 0; i < bos.toString().length(); i++)
        {
            char c = bos.toString().charAt(i);
            if (c != separator && c != endOfLine)
            {
                aux += c;
            }
            else if (c == separator)
            {
                if (readingCollection)
                {
                    collection = aux;
                    readingCollection = false;
                    readingId = true;
                    aux = "";

                }
                else if (readingId)
                {
                    id = aux;
                    readingCollection = false;
                    readingId = false;
                    aux = "";
                }

            }
            else if (c == endOfLine)
            {
                sdate = aux;
                aux = "";
                Date date = sdf.parse(sdate);

                ObservationState os = new ObservationState(new ObservationURI(collection, id));
                os.maxLastModified = date;

                list.add(os);
                readingCollection = true;
                readingId = false;

            }
        }
        Collections.sort(list, maxLasModifiedComparator);
        return list;

    }
}