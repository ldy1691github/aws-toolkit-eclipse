/*
 * Copyright 2011-2012 Amazon Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://aws.amazon.com/apache2.0
 *
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.eclipse.core.regions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.statushandlers.StatusManager;

import com.amazonaws.eclipse.core.AWSClientFactory;
import com.amazonaws.eclipse.core.AwsToolkitCore;
import com.amazonaws.eclipse.core.HttpClientFactory;
import com.amazonaws.eclipse.core.preferences.PreferenceConstants;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;

/**
 * Utilities for working with regions.
 */
public class RegionUtils {

    private static final String CLOUDFRONT_DISTRO = "http://vstoolkit.amazonwebservices.com/";
    private static final String REGIONS_FILE_OVERRIDE = RegionUtils.class.getName() + ".fileOverride";

    private static final String REGIONS_METADATA_S3_BUCKET = "aws-vs-toolkit";
    private static final String REGIONS_METADATA_S3_OBJECT = "ServiceEndPoints.xml";

    private static final String LOCAL_REGION_FILE = "/etc/regions.xml";

    private static List<Region> regions;

    /**
     * Returns true if the specified service is available in the current/active
     * region, otherwise returns false.
     *
     * @param serviceAbbreviation
     *            The abbreviation of the service to check.
     * @return True if the specified service is available in the current/active
     *         region, otherwise returns false.
     * @see ServiceAbbreviations
     */
    public static boolean isServiceSupportedInCurrentRegion(String serviceAbbreviation) {
        return getCurrentRegion().isServiceSupported(serviceAbbreviation);
    }

    /**
     * Returns a list of the available AWS regions.
     */
    public synchronized static List<Region> getRegions() {
        if ( regions == null ) {
            init();
        }

        return regions;
    }

    /**
     * Add a service endpoint to the special "local" region, causing the
     * service to show up in the AWS Explorer when the region is set to local
     * and setting the port that the local service is expected to listen on.
     */
    public synchronized static void addLocalService(
            final String serviceName,
            final String serviceId,
            final int port) {

        Region local = getRegion("local");
        if (local == null) {
            throw new IllegalStateException("No local region found!");
        }

        Service service = new Service(serviceName,
                                      serviceId,
                                      "http://localhost:" + port,
                                      null);

        local.getServicesByName().put(serviceName, service);
        local.getServiceEndpoints().put(serviceName, service.getEndpoint());
    }

    /**
     * Returns a list of the regions that support the service given.
     *
     * @see ServiceAbbreviations
     */
    public synchronized static List<Region> getRegionsForService(String serviceAbbreviation) {
        List<Region> regions = new LinkedList<Region>();
        for ( Region r : getRegions() ) {
            if ( r.isServiceSupported(serviceAbbreviation) ) {
                regions.add(r);
            }
        }
        return regions;
    }

    /**
     * Returns the region with the id given, if it exists. Otherwise, returns null.
     */
    public static Region getRegion(String regionId) {
        for ( Region r : getRegions() ) {
            if ( r.getId().equals(regionId) ) {
                return r;
            }
        }

        return null;
    }

    /**
     * Returns the default/active region that the user previously selected.
     */
    public static Region getCurrentRegion() {
        IPreferenceStore preferenceStore = AwsToolkitCore.getDefault().getPreferenceStore();
        String defaultRegion = preferenceStore.getString(PreferenceConstants.P_DEFAULT_REGION);

        Region rval = getRegion(defaultRegion);

        if (rval == null) {
            throw new RuntimeException("Unable to determine default region");
        }

        return rval;
    }

    /**
     * Searches through the defined services in all regions looking for a
     * service running on the specified endpoint.
     *
     * @param endpoint
     *            The endpoint of the desired service.
     * @return The service running on the specified endpoint.
     *
     * @throws IllegalArgumentException
     *             if no service is found with the specified endpoint.
     */
    public static Service getServiceByEndpoint(String endpoint) {
        for (Region region : regions) {
            for (Service service : region.getServicesByName().values()) {
                if (service.getEndpoint().equals(endpoint)) {
                    return service;
                }
            }
        }

        throw new IllegalArgumentException("Unknown service endpoint: " + endpoint);
    }

    /**
     * Searches through all known regions to find one with any service at the
     * specified endpoint. If no region is found with a service at that
     * endpoint, an exception is thrown.
     *
     * @param endpoint
     *            The endpoint for any service residing in the desired region.
     * @return The region containing any service running at the specified
     *         endpoint, otherwise an exception is thrown if no region is found
     *         with a service at the specified endpoint.
     */
    public static Region getRegionByEndpoint(String endpoint) {
        URL targetEndpointUrl = null;
        try {
            targetEndpointUrl = new URL(endpoint);
        } catch ( MalformedURLException e ) {
            throw new RuntimeException(
                    "Unable to parse service endpoint: " + e.getMessage());
        }

        String targetHost = targetEndpointUrl.getHost();
        for ( Region region : getRegions() ) {
            for ( String serviceEndpoint
                        : region.getServiceEndpoints().values() ) {
                try {
                    URL serviceEndpointUrl = new URL(serviceEndpoint);
                    if ( serviceEndpointUrl.getHost().equals(targetHost) ) {
                        return region;
                    }
                } catch ( MalformedURLException e ) {
                    Status status = new Status(
                        Status.ERROR,
                        AwsToolkitCore.PLUGIN_ID,
                        "Unable to parse service endpoint: " + serviceEndpoint,
                        e);
                    StatusManager.getManager()
                        .handle(status, StatusManager.LOG);
                }
            }
        }

        throw new RuntimeException(
                "No region found with any service for endpoint " + endpoint);
    }


    /**
     * Fetches the most recent version of the regions file from the remote
     * source and caches it to the workspace metadata directory, then
     * initializes the static list of regions with it.
     */
    public static synchronized void init() {

        if (System.getProperty(REGIONS_FILE_OVERRIDE) != null) {
            loadRegionsFromOverrideFile();
        } else {
            IPath stateLocation = Platform.getStateLocation(AwsToolkitCore
                    .getDefault().getBundle());
            File regionsDir = new File(stateLocation.toFile(), "regions");
            File regionsFile = new File(regionsDir, "regions.xml");

            cacheRegionsFile(regionsFile);
            initCachedRegions(regionsFile);
        }
        // Fall back onto the version we ship with the toolkit
        if ( regions == null ) {
            initBundledRegions();
        }

        // If the preference store references an unknown starting region,
        // go ahead and set the starting region to any existing region
        IPreferenceStore preferenceStore = AwsToolkitCore.getDefault().getPreferenceStore();
        Region defaultRegion = getRegion(preferenceStore.getString(PreferenceConstants.P_DEFAULT_REGION));
        if (defaultRegion == null) {
            preferenceStore.setValue(PreferenceConstants.P_DEFAULT_REGION, regions.get(0).getId());
        }
    }

    private static void loadRegionsFromOverrideFile() {
        try {
            System.setProperty("com.amazonaws.sdk.disableCertChecking", "true");
            File regionsFile =
                new File(System.getProperty(REGIONS_FILE_OVERRIDE));
            InputStream override = new FileInputStream(regionsFile);
            regions = parseRegionMetadata(override);
            try {
                cacheFlags(regionsFile.getParentFile());
            } catch ( Exception e ) {
                AwsToolkitCore.getDefault().logException(
                        "Couldn't cache flag icons", e);
            }
        } catch ( Exception e ) {
            AwsToolkitCore.getDefault().logException(
                    "Couldn't load regions override", e);
        }
    }

    /**
     * Caches the regions file stored in cloudfront to the destination file
     * given. Tries S3 if cloudfront is unavailable.
     *
     * If the file in s3 is older than the one on disk, does nothing.
     */
    private static void cacheRegionsFile(File regionsFile) {
        Date regionsFileLastModified = new Date(0);
        if ( !regionsFile.exists() ) {
            regionsFile.getParentFile().mkdirs();
        } else {
            regionsFileLastModified = new Date(regionsFile.lastModified());
        }

        try {
            AmazonS3 s3 =
                AWSClientFactory.getAnonymousS3Client();
            ObjectMetadata objectMetadata =
                s3.getObjectMetadata(REGIONS_METADATA_S3_BUCKET, REGIONS_METADATA_S3_OBJECT);
            if ( objectMetadata.getLastModified()
                        .after(regionsFileLastModified) ) {
                cacheRegionsFile(regionsFile, s3);
            }
        } catch ( Exception e ) {
            AwsToolkitCore.getDefault().logException(
                    "Failed to cache regions file", e);
        }
    }

    /**
     * Tries to initialize the regions list from the file given. If the file
     * doesn't exist or cannot, it is deleted so that it can be fetched cleanly
     * on the next startup.
     */
    private static void initCachedRegions(File regionsFile) {
        try {
            InputStream inputStream = new FileInputStream(regionsFile);
            regions = parseRegionMetadata(inputStream);
            try {
                cacheFlags(regionsFile.getParentFile());
            } catch ( Exception e ) {
                AwsToolkitCore.getDefault().logException(
                        "Couldn't cache flag icons", e);
            }
        } catch ( Exception e ) {
            AwsToolkitCore.getDefault().logException(
                    "Couldn't read regions file", e);
            // Clear out the regions file so that it will get cached again at
            // next startup
            regionsFile.delete();
        }
    }

    /**
     * Failsafe method to initialize the regions list from the list bundled with
     * the plugin, in case it cannot be fetched from the remote source.
     */
    private static void initBundledRegions() {
        ClassLoader classLoader = RegionUtils.class.getClassLoader();
        InputStream inputStream =
            classLoader.getResourceAsStream(LOCAL_REGION_FILE);
        regions = parseRegionMetadata(inputStream);
        for ( Region r : regions ) {
            if (r == LocalRegion.INSTANCE) {
                // No flag to load for the local region.
                continue;
            }

            AwsToolkitCore
                .getDefault()
                .getImageRegistry()
                .put(AwsToolkitCore.IMAGE_FLAG_PREFIX + r.getId(),
                    ImageDescriptor.createFromFile(RegionUtils.class,
                                                   "/icons/" + r.getFlagIconPath()));
        }
    }

    private static final RegionMetadataParser PARSER =
        new RegionMetadataParser();

    /**
     * Parses a list of regions from the given input stream. Adds in the
     * special "local" region.
     */
    private static List<Region> parseRegionMetadata(InputStream inputStream) {
        List<Region> list = PARSER.parseRegionMetadata(inputStream);
        list.add(LocalRegion.INSTANCE);
        return list;
    }

    /**
     * Caches the regions file to the location given
     */
    private static void cacheRegionsFile(File regionsFile, AmazonS3 s3) {
        try {
            truncateFile(regionsFile);
            s3.getObject(new GetObjectRequest(REGIONS_METADATA_S3_BUCKET,
                    REGIONS_METADATA_S3_OBJECT), regionsFile);
        } catch (Exception s3Exception) {
            AwsToolkitCore.getDefault().logException(
                    "Couldn't fetch regions file from s3", s3Exception);
        }
    }

    /**
     * Set the length of the file given to 0 bytes.
     */
    private static void truncateFile(File file)
            throws FileNotFoundException, IOException {
        if ( file.exists() ) {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            raf.getChannel().truncate(0);
            raf.close();
        }
    }

    /**
     * Caches flag icons as necessary, also registering images for them
     */
    private static void cacheFlags(File regionsDir)
            throws ClientProtocolException, IOException {
        if ( !regionsDir.exists() ) {
            return;
        }

        for ( Region r : regions ) {
            if (r == LocalRegion.INSTANCE) {
                // Local region has no flag to initialize.
                continue;
            }

            File icon = new File(regionsDir, r.getFlagIconPath());
            if ( icon.exists() == false ) {
                icon.getParentFile().mkdirs();
                String iconUrl = CLOUDFRONT_DISTRO + r.getFlagIconPath();
                fetchFile(iconUrl, icon);
            }

            AwsToolkitCore
                .getDefault()
                .getImageRegistry()
                .put(AwsToolkitCore.IMAGE_FLAG_PREFIX + r.getId(),
                    ImageDescriptor.createFromURL(
                        icon.getAbsoluteFile().toURI().toURL()));
        }
    }

    /**
     * Fetches a file from the URL given and writes it to the destination given.
     */
    private static void fetchFile(String url, File destinationFile)
            throws IOException, ClientProtocolException, FileNotFoundException {

        HttpClient httpclient = HttpClientFactory.create(
                AwsToolkitCore.getDefault(), url);

        HttpGet httpget = new HttpGet(url);
        HttpResponse response = httpclient.execute(httpget);
        HttpEntity entity = response.getEntity();
        if ( entity != null ) {
            InputStream instream = entity.getContent();
            FileOutputStream output = new FileOutputStream(destinationFile);
            try {
                int l;
                byte[] tmp = new byte[2048];
                while ( (l = instream.read(tmp)) != -1 ) {
                    output.write(tmp, 0, l);
                }
            } finally {
                output.close();
                instream.close();
            }
        }
    }

    /**
     * Load regions from remote S3 bucket.
     */
    public static List<Region> loadRegionsFromS3() {
        AmazonS3 s3 = AWSClientFactory.getAnonymousS3Client();
        InputStream inputStream =
                s3.getObject(REGIONS_METADATA_S3_BUCKET, REGIONS_METADATA_S3_OBJECT)
                    .getObjectContent();
        return parseRegionMetadata(inputStream);
    }

    /**
     * Load regions from local file.
     */
    public static List<Region> loadRegionsFromLocalFile() {
        ClassLoader classLoader = RegionUtils.class.getClassLoader();
        InputStream inputStream =
            classLoader.getResourceAsStream(LOCAL_REGION_FILE);
        return parseRegionMetadata(inputStream);
    }
}
