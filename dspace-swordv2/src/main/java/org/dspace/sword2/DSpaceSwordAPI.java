/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.sword2;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamFormatService;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.BundleService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.core.LogHelper;
import org.dspace.core.Utils;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.swordapp.server.AuthCredentials;
import org.swordapp.server.Deposit;
import org.swordapp.server.DepositReceipt;
import org.swordapp.server.SwordAuthException;
import org.swordapp.server.SwordError;
import org.swordapp.server.SwordServerException;
import org.swordapp.server.UriRegistry;

public class DSpaceSwordAPI {
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DSpaceSwordAPI.class);

    protected ItemService itemService =
        ContentServiceFactory.getInstance().getItemService();

    protected BundleService bundleService =
        ContentServiceFactory.getInstance().getBundleService();

    protected BitstreamService bitstreamService =
        ContentServiceFactory.getInstance().getBitstreamService();

    protected BitstreamFormatService bitstreamFormatService =
        ContentServiceFactory.getInstance().getBitstreamFormatService();

    protected ConfigurationService configurationService
            = DSpaceServicesFactory.getInstance().getConfigurationService();

    public SwordContext noAuthContext()
        throws DSpaceSwordException {
        SwordContext sc = new SwordContext();
        Context context = new Context();
        sc.setContext(context);
        return sc;
    }

    public SwordContext doAuth(AuthCredentials authCredentials)
        throws SwordAuthException, SwordError, DSpaceSwordException {
        // if there is no supplied username, then we should request a retry
        if (authCredentials.getUsername() == null) {
            throw new SwordAuthException(true);
        }

        // first authenticate the request
        // note: this will build our various DSpace contexts for us
        SwordAuthenticator auth = new SwordAuthenticator();
        SwordContext sc = auth.authenticate(authCredentials);

        // log the request
        String un = authCredentials.getUsername() != null ?
            authCredentials.getUsername() :
            "NONE";
        String obo = authCredentials.getOnBehalfOf() != null ?
            authCredentials.getOnBehalfOf() :
            "NONE";
        log.info(LogHelper.getHeader(sc.getContext(), "sword_auth_request",
                                      "username=" + un + ",on_behalf_of=" + obo));

        return sc;
    }

    public String getHeader(Map<String, String> map, String header, String def) {
        for (String key : map.keySet()) {
            if (key.toLowerCase().equals(header.toLowerCase())) {
                return map.get(key);
            }
        }
        return def;
    }

    public TreeMap<Float, List<String>> analyseAccept(String acceptHeader) {
        if (acceptHeader == null) {
            return null;
        }

        String[] parts = acceptHeader.split(",");

        List<Object[]> unsorted = new ArrayList<>();
        float highest_q = 0;
        int counter = 0;
        for (String part : parts) {
            counter += 1;

            // the components of the part can be "type;params;q" "type;params", "type;q" or just "type"
            String[] components = part.split(";");

            // the first part is always the type (see above comment)
            String type = components[0].trim();

            // create some default values for the other parts.  If there is no params, we will use None, if there is
            // no q we will use a negative number multiplied by the position in the list of this part.  This allows us
            // to later see the order in which the parts with no q value were listed, which is important
            String params = null;
            float q = -1 * counter;

            // There are then 3 possibilities remaining to check for: "type;q", "type;params" and "type;params;q"
            // ("type" is already handled by the default cases set up above)
            if (components.length == 2) {
                // "type;q" or "type;params"
                if (components[1].trim().startsWith("q=")) {
                    // "type;q"
                    //strip the "q=" from the start of the q value
                    q = Float.parseFloat(components[1].trim().substring(2));

                    // if the q value is the highest one we've seen so far, record it
                    if (q > highest_q) {
                        highest_q = q;
                    }
                } else {
                    // "type;params"
                    params = components[1].trim();
                }
            } else if (components.length == 3) {
                // "type;params;q"
                params = components[1].trim();
                q = Float.parseFloat(components[1].trim().substring(
                    2)); // strip the "q=" from the start of the q value

                // if the q value is the highest one we've seen so far, record it
                if (q > highest_q) {
                    highest_q = q;
                }
            }

            Object[] res = new Object[] {type, params, q};
            unsorted.add(res);
        }

        // once we've finished the analysis we'll know what the highest explicitly requested q will be.  This may leave
        // us with a gap between 1.0 and the highest requested q, into which we will want to put the content types which
        // did not have explicitly assigned q values.  Here we calculate the size of that gap, so that we can use it
        // later on in positioning those elements.  Note that the gap may be 0.0.
        float q_range = 1 - highest_q;

        // set up a dictionary to hold our sorted results.  The dictionary will be keyed with the q value, and the
        // value of each key will be a list of content type strings (in no particular order)
        TreeMap<Float, List<String>> sorted = new TreeMap<>();

        // go through the unsorted list
        for (Object[] oa : unsorted) {
            String contentType = (String) oa[0];
            String p = (String) oa[1];
            if (p != null) {
                contentType += ";" + p;
            }
            Float qv = (Float) oa[2];

            if (qv > 0) {
                // if the q value is greater than 0 it was explicitly assigned in the Accept header and we can just
                // place
                // it into the sorted dictionary
                if (sorted.containsKey(qv)) {
                    sorted.get(qv).add(contentType);
                } else {
                    List<String> cts = new ArrayList<>();
                    cts.add(contentType);
                    sorted.put(qv, cts);
                }
            } else {
                // otherwise, we have to calculate the q value using the following equation which creates a q value "qv"
                // within "q_range" of 1.0 [the first part of the eqn] based on the fraction of the way through the
                // total
                // accept header list scaled by the q_range [the second part of the eqn]
                float nq = (1 - q_range) + (((-1 * qv) / counter) * q_range);
                if (sorted.containsKey(nq)) {
                    sorted.get(nq).add(contentType);
                } else {
                    List<String> cts = new ArrayList<>();
                    cts.add(contentType);
                    sorted.put(nq, cts);
                }
            }
        }

        return sorted;
    }

    public void isAcceptable(SwordConfigurationDSpace swordConfig,
                             Context context, Deposit deposit, DSpaceObject dso)
        throws SwordError, DSpaceSwordException {
        // determine if this is an acceptable file format
        if (!swordConfig
            .isAcceptableContentType(context, deposit.getMimeType(), dso)) {
            log.error("Unacceptable content type detected: " +
                          deposit.getMimeType() + " for object " + dso.getID());
            throw new SwordError(UriRegistry.ERROR_CONTENT,
                                 "Unacceptable content type in deposit request: " +
                                     deposit.getMimeType());
        }

        // determine if this is an acceptable packaging type for the deposit
        // if not, we throw a 415 HTTP error (Unsupported Media Type, ERROR_CONTENT)
        if (!swordConfig.isAcceptedPackaging(deposit.getPackaging(), dso)) {
            log.error("Unacceptable packaging type detected: " +
                          deposit.getPackaging() + " for object " + dso.getID());
            throw new SwordError(UriRegistry.ERROR_CONTENT,
                                 "Unacceptable packaging type in deposit request: " +
                                     deposit.getPackaging());
        }
    }

    public void storeOriginals(SwordConfigurationDSpace swordConfig,
                               Context context, VerboseDescription verboseDescription,
                               Deposit deposit, DepositResult result)
        throws DSpaceSwordException, SwordServerException {
        // if there's an item available, and we want to keep the original
        // then do that
        try {
            if (swordConfig.isKeepOriginal()) {
                verboseDescription.append(
                    "DSpace will store an original copy of the deposit, " +
                        "as well as ingesting the item into the archive");

                // in order to be allowed to add the file back to the item, we need to ignore authorisations
                // for a moment
                context.turnOffAuthorisationSystem();

                String bundleName = configurationService.getProperty(
                    "swordv2-server.bundle.name");
                if (bundleName == null || "".equals(bundleName)) {
                    bundleName = "SWORD";
                }
                Item item = result.getItem();
                List<Bundle> bundles = item.getBundles();
                Bundle swordBundle = null;
                for (Bundle bundle : bundles) {
                    if (bundleName.equals(bundle.getName())) {
                        swordBundle = bundle;
                        break;
                    }
                }
                if (swordBundle == null) {
                    swordBundle = bundleService.create(
                        context, item, bundleName);
                }

                if (deposit.isMultipart() || deposit.isEntryOnly()) {
                    String entry = deposit.getSwordEntry().toString();
                    ByteArrayInputStream bais = new ByteArrayInputStream(
                        entry.getBytes());
                    Bitstream entryBitstream = bitstreamService.create(
                        context, swordBundle, bais);

                    String fn = this.createEntryFilename(context, deposit, true);
                    entryBitstream.setName(context, fn);
                    entryBitstream.setDescription(context,
                                                  "Original SWORD entry document");

                    BitstreamFormat bf = bitstreamFormatService
                        .findByMIMEType(context, "application/xml");
                    if (bf != null) {
                        entryBitstream.setFormat(context, bf);
                    }

                    bitstreamService.update(context, entryBitstream);

                    verboseDescription.append("Original entry stored as " + fn +
                                                  ", in item bundle " + swordBundle);
                }

                if (deposit.isMultipart() || deposit.isBinaryOnly()) {
                    String fn = this.createFilename(context, deposit, true);

                    Bitstream bitstream;
                    InputStream fis = null;
                    try {
                        fis = deposit.getInputStream();
                        bitstream = bitstreamService
                            .create(context, swordBundle, fis);
                    } finally {
                        if (fis != null) {
                            try {
                                fis.close();
                            } catch (IOException e) {
                                // problem closing input stream; leave it to the garbage collector
                            }
                        }
                    }

                    bitstream.setName(context, fn);
                    bitstream.setDescription(context,
                                             "Original SWORD deposit file");

                    BitstreamFormat bf = bitstreamFormatService
                        .findByMIMEType(context, deposit.getMimeType());
                    if (bf != null) {
                        bitstream.setFormat(context, bf);
                    }

                    bitstreamService.update(context, bitstream);
                    if (result.getOriginalDeposit() == null) {
                        // it may be that the original deposit is already set, in which case we
                        // shouldn't mess with it
                        result.setOriginalDeposit(bitstream);
                    }
                    verboseDescription.append(
                        "Original deposit stored as " + fn +
                            ", in item bundle " + swordBundle);
                }

                bundleService.update(context, swordBundle);
                itemService.update(context, item);

                // now reset the context ignore authorisation
                context.restoreAuthSystemState();
            }
        } catch (SQLException | AuthorizeException | IOException e) {
            log.error("caught exception: ", e);
            throw new DSpaceSwordException(e);
        }
    }

    /**
     * Construct the most appropriate filename for the incoming deposit.
     *
     * @param context  The relevant DSpace Context.
     * @param deposit  deposit request
     * @param original use the ".original" filename suffix?
     * @return filename for deposit
     * @throws DSpaceSwordException can be thrown by the internals of the DSpace SWORD implementation
     */
    public String createFilename(Context context, Deposit deposit,
                                 boolean original)
        throws DSpaceSwordException {
        try {
            BitstreamFormat bf = bitstreamFormatService
                .findByMIMEType(context, deposit.getMimeType());
            List<String> exts = null;
            if (bf != null) {
                exts = bf.getExtensions();
            }

            String fn = deposit.getFilename();
            if (fn == null || "".equals(fn)) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
                fn = "sword-" + formatter.format(LocalDateTime.now(ZoneOffset.UTC));
                if (original) {
                    fn = fn + ".original";
                }
                if (exts != null) {
                    fn = fn + "." + exts.get(0);
                }
            }

            return fn;
        } catch (SQLException e) {
            throw new DSpaceSwordException(e);
        }
    }

    public String createEntryFilename(Context context, Deposit deposit,
                                      boolean original)
        throws DSpaceSwordException {

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        String fn = "sword-" + formatter.format(LocalDateTime.now(ZoneOffset.UTC));
        if (original) {
            fn = fn + ".original";
        }

        return fn + ".xml";
    }

    /**
     * Store original package on disk and companion file containing SWORD headers as found in the deposit object
     * Also write companion file with header info from the deposit object.
     *
     * @param deposit deposit object to store as package
     * @param auth    authentication credentials
     * @param config  SWORD configuration
     * @throws IOException A general class of exceptions produced by failed or interrupted I/O operations.
     */
    protected void storePackageAsFile(Deposit deposit, AuthCredentials auth,
                                      SwordConfigurationDSpace config) throws IOException {
        String path = config.getFailedPackageDir();

        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IOException(
                "Directory does not exist for writing packages on ingest error.");
        }

        String filenameBase =
            "sword-" + auth.getUsername() + "-" + Instant.now().toEpochMilli();

        File packageFile = new File(path, filenameBase);
        File headersFile = new File(path, filenameBase + "-headers");

        InputStream is = new BufferedInputStream(
            new FileInputStream(deposit.getFile()));
        OutputStream fos = new BufferedOutputStream(
            new FileOutputStream(packageFile));
        Utils.copy(is, fos);
        fos.close();
        is.close();

        //write companion file with headers
        PrintWriter pw = new PrintWriter(
            new BufferedWriter(new FileWriter(headersFile)));

        pw.println("Filename=" + deposit.getFilename());
        pw.println("Content-Type=" + deposit.getMimeType());
        pw.println("Packaging=" + deposit.getPackaging());
        pw.println("On Behalf of=" + auth.getOnBehalfOf());
        pw.println("Slug=" + deposit.getSlug());
        pw.println("User name=" + auth.getUsername());
        pw.close();
    }

    /**
     * Store original package on disk and companion file containing SWORD headers as found in the deposit object
     * Also write companion file with header info from the deposit object.
     *
     * @param deposit deposit object to store as package
     * @param auth    authentication credentials
     * @param config  SWORD configuration
     * @throws IOException A general class of exceptions produced by failed or interrupted I/O operations.
     */
    protected void storeEntryAsFile(Deposit deposit, AuthCredentials auth,
                                    SwordConfigurationDSpace config) throws IOException {
        String path = config.getFailedPackageDir();

        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IOException(
                "Directory does not exist for writing packages on ingest error.");
        }

        String filenameBase =
            "sword-" + auth.getUsername() + "-" + Instant.now().toEpochMilli();

        File packageFile = new File(path, filenameBase);
        File headersFile = new File(path, filenameBase + "-headers");

        String entry = deposit.getSwordEntry().toString();
        ByteArrayInputStream is = new ByteArrayInputStream(entry.getBytes());
        OutputStream fos = new BufferedOutputStream(
            new FileOutputStream(packageFile));
        Utils.copy(is, fos);
        fos.close();
        is.close();

        //write companion file with headers
        PrintWriter pw = new PrintWriter(
            new BufferedWriter(new FileWriter(headersFile)));

        pw.println("Filename=" + deposit.getFilename());
        pw.println("Content-Type=" + deposit.getMimeType());
        pw.println("Packaging=" + deposit.getPackaging());
        pw.println("On Behalf of=" + auth.getOnBehalfOf());
        pw.println("Slug=" + deposit.getSlug());
        pw.println("User name=" + auth.getUsername());
        pw.close();
    }

    protected void addVerboseDescription(DepositReceipt receipt,
                                         VerboseDescription verboseDescription) {
        boolean includeVerbose = configurationService
            .getBooleanProperty("swordv2-server.verbose-description.receipt.enable");
        if (includeVerbose) {
            receipt.setVerboseDescription(verboseDescription.toString());
        }
    }
}
