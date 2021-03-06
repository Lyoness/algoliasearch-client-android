/*
 * Copyright (c) 2015 Algolia
 * http://www.algolia.com/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.algolia.search.saas;

import android.annotation.SuppressLint;

import com.algolia.search.saas.helpers.DisjunctiveFaceting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.internal.util.reflection.Whitebox;
import org.robolectric.util.concurrent.RoboExecutorService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <a href="http://d.android.com/tools/testing/testing_android.html">Testing Fundamentals</a>
 */

@SuppressWarnings("unchecked") //Whitebox requires casts from Object
@SuppressLint("DefaultLocale") //We use format for logging errors, locale issues are irrelevant
public class IndexTest extends RobolectricTestCase {
    private static final int COUNT_TEST_INDICES = 50; //TODO: Count test methods with reflection?

    private static String originalIndexName = Helpers.safeIndexName("àlgol?à-android");
    private static List<String> originalIds;
    private static List<JSONObject> originalObjects;
    private static int countIndices;
    private static boolean didInitIndices = false;

    Client client;
    Index index;
    String indexName;

    List<JSONObject> objects;
    List<String> ids;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        client = new Client(Helpers.app_id, Helpers.api_key);
        // WARNING: Robolectric cannot work with custom executors in `AsyncTask`, so we substitute the client's
        // executor with a Robolectric-compliant one.
        Whitebox.setInternalState(client, "searchExecutorService", new RoboExecutorService());

        if (!didInitIndices) {
            Index originalIndex = client.getIndex(originalIndexName);
            client.deleteIndex(originalIndexName);

            originalObjects = new ArrayList<>();
            originalObjects.add(new JSONObject("{\"city\": \"San Francisco\", \"state\": \"CA\"}"));
            originalObjects.add(new JSONObject("{\"city\": \"San José\", \"state\": \"CA\"}"));

            JSONObject task = originalIndex.addObjects(new JSONArray(originalObjects));
            originalIndex.waitTask(task.getString("taskID"));

            JSONArray objectIDs = task.getJSONArray("objectIDs");
            originalIds = new ArrayList<>();
            for (int i = 0; i < objectIDs.length(); ++i) {
                originalIds.add(objectIDs.getString(i));
            }

            didInitIndices = true;

            for (int i = 0; i < COUNT_TEST_INDICES; i++) {
                final String iterIndexName = originalIndexName + i;
                client.deleteIndex(iterIndexName);
                task = client.copyIndex(originalIndexName, iterIndexName);
            }
            originalIndex.waitTask(task.getString("taskID"));
        }

        ids = new ArrayList<>(originalIds);
        objects = new ArrayList<>(originalObjects);
        indexName = originalIndexName + countIndices++;
        index = client.getIndex(indexName);
    }

    @Override
    public void tearDown() {
        try {
            client.deleteIndex(indexName);
        }
        catch (AlgoliaException e) {
            fail(e.getMessage());
        }
        AssertCompletionHandler.checkAllHandlers();
    }

    @Test
    public void searchAsync() throws Exception {
        searchAsync(Helpers.wait);
    }

    public void searchAsync(int waitTimeoutSeconds) throws Exception {
        final long begin = System.nanoTime();
        // Search with query.
        index.searchAsync(new Query("Francisco"), new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertEquals(1, content.optInt("nbHits"));
                } else {
                    fail(error.getMessage());
                }
            }
        });

        final long elapsedMillis = (System.nanoTime() - begin) / 1000000;
        final int waitTimeoutMillis = waitTimeoutSeconds * 1000;
        assertTrue("The test took longer than given timeout (" + elapsedMillis + " > " + waitTimeoutMillis + ").", elapsedMillis <= waitTimeoutMillis);
    }

    @Test
    public void searchDisjunctiveFacetingAsync() throws Exception {
        // Set index settings.
        JSONObject setSettingsResult = index.setSettings(new JSONObject("{\"attributesForFaceting\": [\"brand\", \"category\"]}"));
        index.waitTask(setSettingsResult.getString("taskID"));

        // Empty query
        // -----------
        // Not very useful, but we have to check this edge case.
        index.searchDisjunctiveFacetingAsync(new Query(), new ArrayList<String>(), new HashMap<String, List<String>>(), new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertEquals("Result length does not match nbHits", objects.size(), content.optInt("nbHits"));
                } else {
                    fail(error.getMessage());
                }
            }
        });

        // "Real" query
        // ------------
        // Create data set.
        objects = new ArrayList<>();
        objects.add(new JSONObject("{\"name\": \"iPhone 6\", \"brand\": \"Apple\", \"category\": \"device\",\"stars\":4}"));
        objects.add(new JSONObject("{\"name\": \"iPhone 6 Plus\", \"brand\": \"Apple\", \"category\": \"device\",\"stars\":5}"));
        objects.add(new JSONObject("{\"name\": \"iPhone cover\", \"brand\": \"Apple\", \"category\": \"accessory\",\"stars\":3}"));
        objects.add(new JSONObject("{\"name\": \"Galaxy S5\", \"brand\": \"Samsung\", \"category\": \"device\",\"stars\":4}"));
        objects.add(new JSONObject("{\"name\": \"Wonder Phone\", \"brand\": \"Samsung\", \"category\": \"device\",\"stars\":5}"));
        objects.add(new JSONObject("{\"name\": \"Platinum Phone Cover\", \"brand\": \"Samsung\", \"category\": \"accessory\",\"stars\":2}"));
        objects.add(new JSONObject("{\"name\": \"Lame Phone\", \"brand\": \"Whatever\", \"category\": \"device\",\"stars\":1}"));
        objects.add(new JSONObject("{\"name\": \"Lame Phone cover\", \"brand\": \"Whatever\", \"category\": \"accessory\",\"stars\":1}"));
        JSONObject task = index.addObjects(new JSONArray(objects));
        index.waitTask(task.getString("taskID"));

        final Query query = new Query("phone").setFacets("brand", "category", "stars");
        final List<String> disjunctiveFacets = Collections.singletonList("brand");
        final Map<String, List<String>> refinements = new HashMap<>();
        refinements.put("brand", Arrays.asList("Apple", "Samsung")); // disjunctive facet
        refinements.put("category", Collections.singletonList("device")); // conjunctive facet
        index.searchDisjunctiveFacetingAsync(query, disjunctiveFacets, refinements, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error != null) {
                    fail(error.getMessage());
                } else {
                    assertEquals(3, content.optInt("nbHits"));
                    JSONObject disjunctiveFacetsResult = content.optJSONObject("disjunctiveFacets");
                    assertNotNull(disjunctiveFacetsResult);
                    JSONObject brandFacetCounts = disjunctiveFacetsResult.optJSONObject("brand");
                    assertNotNull(brandFacetCounts);
                    assertEquals(2, brandFacetCounts.optInt("Apple"));
                    assertEquals(1, brandFacetCounts.optInt("Samsung"));
                    assertEquals(1, brandFacetCounts.optInt("Whatever"));
                }
            }
        });
    }

    @Test
    public void disjunctiveFacetingAsync2() throws Exception {
        // Set index settings.
        JSONObject setSettingsResult = index.setSettings(new JSONObject("{\"attributesForFaceting\":[\"city\", \"stars\", \"facilities\"]}"));
        index.waitTask(setSettingsResult.getString("taskID"));

        // Add objects.
        JSONObject addObjectsResult = index.addObjects(new JSONArray()
                .put(new JSONObject("{\"name\":\"Hotel A\", \"stars\":\"*\", \"facilities\":[\"wifi\", \"bath\", \"spa\"], \"city\":\"Paris\"}"))
                .put(new JSONObject("{\"name\":\"Hotel B\", \"stars\":\"*\", \"facilities\":[\"wifi\"], \"city\":\"Paris\"}"))
                .put(new JSONObject("{\"name\":\"Hotel C\", \"stars\":\"**\", \"facilities\":[\"bath\"], \"city\":\"San Fancisco\"}"))
                .put(new JSONObject("{\"name\":\"Hotel D\", \"stars\":\"****\", \"facilities\":[\"spa\"], \"city\":\"Paris\"}"))
                .put(new JSONObject("{\"name\":\"Hotel E\", \"stars\":\"****\", \"facilities\":[\"spa\"], \"city\":\"New York\"}")));
        index.waitTask(addObjectsResult.getString("taskID"));

        // Search.
        final Query query = new Query("h").setFacets("city");
        final List<String> disjunctiveFacets = Arrays.asList("stars", "facilities");
        final Map<String, List<String>> refinements = new HashMap<>();

        index.searchDisjunctiveFacetingAsync(query, disjunctiveFacets, refinements, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertEquals(5, content.optInt("nbHits"));
                assertEquals(1, content.optJSONObject("facets").length());
                assertEquals(2, content.optJSONObject("disjunctiveFacets").length());
            }
        });

        refinements.put("stars", Collections.singletonList("*"));
        index.searchDisjunctiveFacetingAsync(query, disjunctiveFacets, refinements, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertEquals(2, content.optInt("nbHits"));
                assertEquals(1, content.optJSONObject("facets").length());
                assertEquals(2, content.optJSONObject("disjunctiveFacets").length());
                assertEquals(2, content.optJSONObject("disjunctiveFacets").optJSONObject("stars").optInt("*"));
                assertEquals(1, content.optJSONObject("disjunctiveFacets").optJSONObject("stars").optInt("**"));
                assertEquals(2, content.optJSONObject("disjunctiveFacets").optJSONObject("stars").optInt("****"));
            }
        });

        refinements.put("city", Collections.singletonList("Paris"));
        index.searchDisjunctiveFacetingAsync(query, disjunctiveFacets, refinements, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertEquals(2, content.optInt("nbHits"));
                assertEquals(1, content.optJSONObject("facets").length());
                assertEquals(2, content.optJSONObject("disjunctiveFacets").length());
                assertEquals(2, content.optJSONObject("disjunctiveFacets").optJSONObject("stars").optInt("*"));
                assertEquals(1, content.optJSONObject("disjunctiveFacets").optJSONObject("stars").optInt("****"));
            }
        });

        refinements.put("stars", Arrays.asList("*", "****"));
        index.searchDisjunctiveFacetingAsync(query, disjunctiveFacets, refinements, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {

                assertEquals(3, content.optInt("nbHits"));
                assertEquals(1, content.optJSONObject("facets").length());
                assertEquals(2, content.optJSONObject("disjunctiveFacets").length());
                assertEquals(2, content.optJSONObject("disjunctiveFacets").optJSONObject("stars").optInt("*"));
                assertEquals(1, content.optJSONObject("disjunctiveFacets").optJSONObject("stars").optInt("****"));
            }
        });
    }

    @Test
    public void aggregateResultsPropagatesNonExhaustiveCount() throws Exception {
        try {
            List<String> disjunctiveFacets = new ArrayList<>();
            Map<String, List<String>> refinements = new HashMap<>();

            JSONObject answers = new JSONObject().put("results", new JSONArray()
                    .put(new JSONObject("{\"facets\": {},\"exhaustiveFacetsCount\": true}"))
                    .put(new JSONObject("{\"facets\": {},\"exhaustiveFacetsCount\": true}")));
            JSONObject result = org.powermock.reflect.Whitebox.invokeMethod(DisjunctiveFaceting.class, "aggregateDisjunctiveFacetingResults", answers, disjunctiveFacets, refinements);
            assertTrue("If all results have exhaustive counts, the aggregated one should too.", result.getBoolean("exhaustiveFacetsCount"));

            answers = new JSONObject().put("results", new JSONArray()
                    .put(new JSONObject("{\"facets\": {},\"exhaustiveFacetsCount\": false}"))
                    .put(new JSONObject("{\"facets\": {},\"exhaustiveFacetsCount\": true}")));
            result = org.powermock.reflect.Whitebox.invokeMethod(DisjunctiveFaceting.class, "aggregateDisjunctiveFacetingResults", answers, disjunctiveFacets, refinements);
            assertFalse("If some results have non-exhaustive counts, neither should the aggregated one.", result.getBoolean("exhaustiveFacetsCount"));

            answers = new JSONObject().put("results", new JSONArray()
                    .put(new JSONObject("{\"facets\": {},\"exhaustiveFacetsCount\": true}"))
                    .put(new JSONObject("{\"facets\": {},\"exhaustiveFacetsCount\": false}")));
            result = org.powermock.reflect.Whitebox.invokeMethod(DisjunctiveFaceting.class, "aggregateDisjunctiveFacetingResults", answers, disjunctiveFacets, refinements);
            assertFalse("If some results have non-exhaustive counts, neither should the aggregated one.", result.getBoolean("exhaustiveFacetsCount"));

            answers = new JSONObject().put("results", new JSONArray()
                    .put(new JSONObject("{\"facets\": {},\"exhaustiveFacetsCount\": false}"))
                    .put(new JSONObject("{\"facets\": {},\"exhaustiveFacetsCount\": false}")));
            result = org.powermock.reflect.Whitebox.invokeMethod(DisjunctiveFaceting.class, "aggregateDisjunctiveFacetingResults", answers, disjunctiveFacets, refinements);
            assertFalse("If no results have exhaustive counts, neither should the aggregated one.", result.getBoolean("exhaustiveFacetsCount"));

        } catch (Exception e) {
            throw e;
        }
    }

    @Test
    public void addObjectAsync() throws Exception {
        index.addObjectAsync(new JSONObject("{\"city\": \"New York\"}"), new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertNotNull("Result has no objectId:" + content, content.optString("objectID", null));
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void addObjectWithObjectIDAsync() throws Exception {
        index.addObjectAsync(new JSONObject("{\"city\": \"New York\"}"), "a1b2c3", new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertTrue("Object has unexpected objectId", content.optString("objectID").equals("a1b2c3"));
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void addObjectsAsync() throws Exception {
        index.addObjectsAsync(new JSONArray("[{\"city\": \"New York\"}, {\"city\": \"Paris\"}]"), new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertEquals("Objects have unexpected objectId count", 2, content.optJSONArray("objectIDs").length());
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void saveObjectAsync() throws Exception {
        index.saveObjectAsync(new JSONObject("{\"city\": \"New York\"}"), "a1b2c3", new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertTrue("Object has unexpected objectId", content.optString("objectID").equals("a1b2c3"));
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void saveObjectsAsync() throws Exception {
        index.saveObjectsAsync(new JSONArray("[{\"city\": \"New York\", \"objectID\": 123}, {\"city\": \"Paris\", \"objectID\": 456}]"), new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertEquals("Objects have unexpected objectId count", 2, content.optJSONArray("objectIDs").length());
                    assertEquals("Object has unexpected objectId", 123, content.optJSONArray("objectIDs").optInt(0));
                    assertEquals("Object has unexpected objectId", 456, content.optJSONArray("objectIDs").optInt(1));
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void getObjectAsync() throws Exception {
        index.getObjectAsync(ids.get(0), new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertTrue("Object has unexpected objectId", content.optString("objectID").equals(ids.get(0)));
                    assertTrue("Object has unexpected 'city' attribute", content.optString("city").equals("San Francisco"));
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void getObjectWithAttributesToRetrieveAsync() throws Exception {
        List<String> attributesToRetrieve = Arrays.asList("objectID", "state");
        index.getObjectAsync(ids.get(0), attributesToRetrieve, new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertTrue("Object has unexpected objectId", content.optString("objectID").equals(ids.get(0)));
                    assertTrue("Object is missing expected 'state' attribute", content.has("state"));
                    assertFalse("Object has unexpected 'city' attribute", content.has("city"));
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void getObjectsAsync() throws Exception {
        index.getObjectsAsync(ids, new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    JSONArray res = content.optJSONArray("results");
                    assertNotNull(res);
                    assertTrue("Object has unexpected objectId", res.optJSONObject(0).optString("objectID").equals(ids.get(0)));
                    assertTrue("Object has unexpected objectId", res.optJSONObject(1).optString("objectID").equals(ids.get(1)));
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void getObjectsWithAttributesToRetrieveAsync() throws Exception {
        List<String> attributesToRetrieve = new ArrayList<>();
        attributesToRetrieve.add("objectID");
        index.getObjectsAsync(ids, attributesToRetrieve, new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    JSONArray res = content.optJSONArray("results");
                    assertNotNull(res);
                    assertTrue("Object has unexpected objectId", res.optJSONObject(0).optString("objectID").equals(ids.get(0)));
                    assertFalse("Object has unexpected 'city' attribute", res.optJSONObject(1).has("city"));
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void waitTaskAsync() throws Exception {
        index.addObjectAsync(new JSONObject("{\"city\": \"New York\"}"), new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    index.waitTaskAsync(content.optInt("taskID"), new AssertCompletionHandler() {
                        @Override
                        public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                            if (error == null) {
                                assertEquals(content.optString("status"), "published");
                            } else {
                                fail(error.getMessage());
                            }
                        }
                    });
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void hostSwitch() throws Exception {
        // Given first host as an unreachable domain
        List<String> hostsArray = (List<String>) Whitebox.getInternalState(client, "readHosts");
        hostsArray.set(0, "thissentenceshouldbeuniqueenoughtoguaranteeinexistentdomain.com");
        Whitebox.setInternalState(client, "readHosts", hostsArray);

        // Expect a switch to the next URL and successful search
        searchAsync();
    }

    @Test
    public void DNSTimeout() throws Exception {
        // On Travis, the reported run duration is not reliable.
        if ("true".equals(System.getenv("TRAVIS"))) {
            return;
        }

        // Given all hosts resulting in a DNS Timeout
        String appId = (String) Whitebox.getInternalState(client, "applicationID");
        List<String> hostsArray = (List<String>) Whitebox.getInternalState(client, "readHosts");
        final String hostName = appId + "-dsn.algolia.biz"; //TODO: Random host name to avoid system DNS cache
        hostsArray.set(0, hostName);
        hostsArray.set(1, hostName);
        hostsArray.set(2, hostName);
        hostsArray.set(3, hostName);
        Whitebox.setInternalState(client, "readHosts", hostsArray);

        //A connect timeout of 500 ms
        final int timeout = 500;
        client.setConnectTimeout(timeout);

        //And an index that does not cache search queries
        index.disableSearchCache();


        // Expect failed search after timeout
        final long startTime = System.nanoTime();
        index.searchAsync(new Query(), new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error != null) {
                    final long duration = (System.nanoTime() - startTime) / 1000000;
                    assertTrue("We should hit 4 times the timeout before failing, but test took only " + duration + " ms.", duration > timeout * 4);
                } else {
                    fail("Searching with failing hosts should result in an error.");
                }
            }
        });
    }

    @Test
    public void connectTimeout() throws AlgoliaException {
        connectTimeout(1);
    }

    @Test
    public void multipleConnectTimeout() throws AlgoliaException {
        connectTimeout(2);
    }

    private void connectTimeout(int nbHosts) throws AlgoliaException {
        // On Travis, the reported run duration is not reliable.
        if ("true".equals(System.getenv("TRAVIS"))) {
            return;
        }

        int timeout = 1000;
        nbHosts = Math.min(nbHosts, 4);
        int maxMillis = (nbHosts + 1) * timeout;

        final List<String> hostsArray = (List<String>) Whitebox.getInternalState(client, "readHosts");
        for (int i = 0; i < nbHosts; i++) {
            hostsArray.set(i, "notcp-xx-1.algolianet.com");
        }
        Whitebox.setInternalState(client, "readHosts", hostsArray);

        client.setConnectTimeout(1000);
        client.setReadTimeout(1000);

        Long start = System.currentTimeMillis();
        assertNotNull("listIndexes() should return.", client.listIndexes());
        final long totalMillis = System.currentTimeMillis() - start;
        assertTrue(String.format("The test ran longer than expected (%d > %dms).", totalMillis, maxMillis), totalMillis <= maxMillis);
    }


    @Test
    public void connectionResetException() throws IOException, AlgoliaException {
        Thread runnable = new Thread() {
            @Override
            public void run() {
                try {
                    ServerSocket serverSocket = new ServerSocket(8080);
                    Socket socket = serverSocket.accept();
                    socket.setSoLinger(true, 0);
                    socket.close();
                } catch (IOException ignored) {
                    ignored.printStackTrace();
                }
            }
        };

        runnable.start();

        List<String> hostsArray = (List<String>) Whitebox.getInternalState(client, "readHosts");
        hostsArray.set(0, "localhost:8080");
        hostsArray.set(1, "notcp-xx-1.algolianet.com");

        client.setConnectTimeout(1000);
        client.setReadTimeout(1000);

        Long start = System.currentTimeMillis();
        assertNotNull("listIndexes() should return.", client.listIndexes());
        long end = System.currentTimeMillis() - start;
        assertTrue("The test ran longer than expected (" + end + "ms > 2s)", end < 2 * 1000);
    }

    @Test
    public void SNI() throws Exception {
        // Given all hosts using SNI
        String appId = (String) Whitebox.getInternalState(client, "applicationID");
        List<String> hostsArray = (List<String>) Whitebox.getInternalState(client, "readHosts");
        hostsArray.set(0, appId + "-1.algolianet.com");
        hostsArray.set(1, appId + "-2.algolianet.com");
        hostsArray.set(2, appId + "-3.algolianet.com");
        hostsArray.set(3, appId + "-3.algolianet.com");
        Whitebox.setInternalState(client, "readHosts", hostsArray);

        // Expect correct certificate handling and successful search
        searchAsync();
    }

    @Test
    public void keepAlive() throws Exception {
        // On Travis, the reported run duration is not reliable.
        if ("true".equals(System.getenv("TRAVIS"))) {
            return;
        }

        final int nbTimes = 10;

        // Given all hosts being the same one
        String appId = (String) Whitebox.getInternalState(client, "applicationID");
        List<String> hostsArray = (List<String>) Whitebox.getInternalState(client, "readHosts");
        hostsArray.set(0, appId + "-1.algolianet.com");
        hostsArray.set(1, appId + "-1.algolianet.com");
        hostsArray.set(2, appId + "-1.algolianet.com");
        hostsArray.set(3, appId + "-1.algolianet.com");
        Whitebox.setInternalState(client, "readHosts", hostsArray);

        //And an index that does not cache search queries
        index.disableSearchCache();


        // Expect first successful search
        final long[] startEndTimeArray = new long[2];
        startEndTimeArray[0] = System.nanoTime();
        index.searchAsync(new Query("Francisco"), new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    assertEquals(1, content.optInt("nbHits"));
                    startEndTimeArray[1] = System.nanoTime();
                } else {
                    fail(error.getMessage());
                }
            }
        });

        final long firstDurationNanos = startEndTimeArray[1] - startEndTimeArray[0];
        for (int i = 0; i < nbTimes; i++) {
            startEndTimeArray[0] = System.nanoTime();
            final int finalIter = i;
            index.searchAsync(new Query("Francisco"), new AssertCompletionHandler() {
                @Override
                public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                    if (error == null) {
                        startEndTimeArray[1] = System.nanoTime();
                        final long iterDiff = startEndTimeArray[1] - startEndTimeArray[0];
                        final String iterString = String.format("iteration %d: %d < %d", finalIter, iterDiff, firstDurationNanos);

                        // And successful fastest subsequent calls
                        assertEquals(1, content.optInt("nbHits"));
                        assertTrue("Subsequent calls should be fastest than first (" + iterString + ")", startEndTimeArray[1] - startEndTimeArray[0] < firstDurationNanos);
                    } else {
                        fail(error.getMessage());
                    }
                }
            });
        }
    }


    private void addDummyObjects(int objectCount) throws Exception {
        // Construct an array of dummy objects.
        objects = new ArrayList<>();
        for (int i = 0; i < objectCount; ++i) {
            objects.add(new JSONObject(String.format("{\"dummy\": %d}", i)));
        }

        // Add objects.
        JSONObject task = index.addObjects(new JSONArray(objects));
        index.waitTask(task.getString("taskID"));
    }

    @Test
    public void browseAsync() throws Exception {
        addDummyObjects(1500);
        Query query = new Query().setHitsPerPage(1000);
        index.browseAsync(query, new AssertCompletionHandler() {
            @Override public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    String cursor = content.optString("cursor", null);
                    assertNotNull(cursor);
                    index.browseFromAsync(cursor, new AssertCompletionHandler() {
                        @Override
                        public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                            if (error == null) {
                                String cursor = content.optString("cursor", null);
                                assertNull(cursor);
                            } else {
                                fail(error.getMessage());
                            }
                        }
                    });
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void clearIndexAsync() throws Exception {
        index.clearIndexAsync(new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    index.waitTaskAsync(content.optString("taskID"), new AssertCompletionHandler() {
                        @Override
                        public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                            if (error == null) {
                                index.browseAsync(new Query(), new AssertCompletionHandler() {
                                    @Override
                                    public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                        if (error == null) {
                                            assertEquals(content.optInt("nbHits"), 0);
                                        } else {
                                            fail(error.getMessage());
                                        }
                                    }
                                });
                            } else {
                                fail(error.getMessage());
                            }
                        }
                    });
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void deleteByQueryAsync() throws Exception {
        addDummyObjects(3000);
        final Query query = new Query().setNumericFilters(new JSONArray().put("dummy < 1500"));
        index.deleteByQueryAsync(query, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error == null) {
                    index.browseAsync(query, new AssertCompletionHandler() {
                        @Override
                        public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                            if (error == null) {
                                // There should not remain any object matching the query.
                                assertNotNull(content.optJSONArray("hits"));
                                assertEquals(content.optJSONArray("hits").length(), 0);
                                assertNull(content.optString("cursor", null));
                            } else {
                                fail(error.getMessage());
                            }
                        }
                    });
                } else {
                    fail(error.getMessage());
                }
            }
        });
    }

    @Test
    public void error404() throws Exception {
        Index unknownIndex = client.getIndex("doesnotexist");
        unknownIndex.searchAsync(new Query(), new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertNotNull(error);
                assertEquals(404, error.getStatusCode());
                assertNotNull(error.getMessage());
            }
        });
    }

    @Test
    public void cacheUseIfEnabled() throws Exception {
        index.enableSearchCache();
        verifySearchTwiceCalls(1);
    }

    @Test
    public void cacheDontUseByDefault() throws Exception {
        verifySearchTwiceCalls(2);
    }

    @Test
    public void cacheDontUseIfDisabled() throws Exception {
        index.disableSearchCache();
        verifySearchTwiceCalls(2);
    }

    @Test
    public void cacheTimeout() throws Exception {
        index.enableSearchCache(1, ExpiringCache.defaultMaxSize);
        verifySearchTwiceCalls(2, 2);
    }

    /**
     * Verifies the number of requests fired by two successive search queries
     *
     * @param nbTimes expected amount of requests
     */
    private void verifySearchTwiceCalls(int nbTimes) throws Exception {
        verifySearchTwiceCalls(nbTimes, 0);
    }

    /**
     * Verifies the number of requests fired by two search queries
     *
     * @param nbTimes            expected amount of requests
     * @param waitBetweenSeconds optional time to wait between the two queries
     */
    private void verifySearchTwiceCalls(int nbTimes, int waitBetweenSeconds) throws Exception {
        // Given a index, using a client that returns some json on search
        Client mockClient = mock(Client.class);
        Whitebox.setInternalState(index, "client", mockClient);
        when(mockClient.postRequestRaw(anyString(), anyString(), anyBoolean())).thenReturn("{foo:42}".getBytes());

        // When searching twice separated by waitBetweenSeconds, fires nbTimes requests
        final Query query = new Query("San");
        index.search(query);
        if (waitBetweenSeconds > 0) {
            Thread.sleep(waitBetweenSeconds * 1000);
        }
        index.search(query);
        verify(mockClient, times(nbTimes)).postRequestRaw(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void nullCompletionHandler() throws Exception {
        // Check that the code does not crash when no completion handler is specified.
        index.addObjectAsync(new JSONObject("{\"city\": \"New York\"}"), null);
    }

    @Test
    public void multipleQueries() throws Exception {
        final List<Query> queries = Arrays.asList(
                new Query("francisco").setHitsPerPage(1),
                new Query("jose")
        );
        index.multipleQueriesAsync(queries, Client.MultipleQueriesStrategy.STOP_IF_ENOUGH_MATCHES, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                if (error != null) {
                    fail(error.getMessage());
                } else {
                    JSONArray results = content.optJSONArray("results");
                    assertNotNull(results);
                    assertEquals(results.length(), 2);
                    JSONObject results1 = results.optJSONObject(0);
                    assertNotNull(results1);
                    assertEquals(results1.optInt("nbHits"), 1);
                    JSONObject results2 = results.optJSONObject(1);
                    assertNotNull(results2);
                    assertEquals(results2.optBoolean("processed", true), false);
                    assertEquals(results2.optInt("nbHits"), 0);
                }
            }
        });
    }

    @Test
    public void userAgent() throws Exception {
        // Test the default value.
        String userAgent = (String) Whitebox.getInternalState(client, "userAgentRaw");
        assertTrue(userAgent.matches("^Algolia for Android \\([0-9.]+\\); Android \\(([0-9.]+(_r[0-9]+)?|unknown)\\)$"));

        // Manipulate the list.
        assertFalse(client.hasUserAgent(new Client.LibraryVersion("toto", "6.6.6")));
        client.addUserAgent(new Client.LibraryVersion("toto", "6.6.6"));
        assertTrue(client.hasUserAgent(new Client.LibraryVersion("toto", "6.6.6")));
        userAgent = (String) Whitebox.getInternalState(client, "userAgentRaw");
        assertTrue(userAgent.matches("^.*; toto \\(6.6.6\\)$"));
    }

    @Test
    public void getObjectAttributes() throws AlgoliaException {
        for (String id : ids) {
            JSONObject object = index.getObject(id);
            assertEquals("The retrieved object should have 3 attributes.", 3, object.names().length()); // 2 attributes + `objectID`
            object = index.getObject(id, Collections.singletonList("city"));
            assertEquals("The retrieved object should have 2 attributes.", 2, object.names().length()); // 1 attribute + `objectID`
            assertNotNull("The retrieved object should have an `objectID` attribute.", object.optString("objectID", null));
            assertNotNull("The retrieved object should have a `city` attribute.", object.optString("city", null));
        }
    }

    @Test
    public void getObjectsAttributes() throws AlgoliaException {
        try {
            JSONArray results = index.getObjects(ids).getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                JSONObject object = results.getJSONObject(i);
                assertEquals("The retrieved object should have 3 attributes.", 3, object.names().length()); // 2 attributes + `objectID`
            }
            results = index.getObjects(ids, Collections.singletonList("city")).getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                JSONObject object = results.getJSONObject(i);
                assertEquals("The retrieved object should have 2 attributes.", 2, object.names().length()); // 1 attribute + `objectID`
                assertNotNull("The retrieved object should have an `objectID` attribute.", object.optString("objectID", null));
                assertNotNull("The retrieved object should have a `city` attribute.", object.optString("city", null));
            }
        } catch (JSONException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void partialUpdateObject() throws Exception {
        JSONObject partialObject = new JSONObject().put("city", "Paris");
        index.partialUpdateObjectAsync(partialObject, ids.get(0), new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertNull(error);
                String taskID = content.optString("taskID");
                index.waitTaskAsync(taskID, new AssertCompletionHandler() {
                    @Override
                    public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                        assertNull(error);
                        index.getObjectAsync(ids.get(0), new AssertCompletionHandler() {
                            @Override
                            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                assertNotNull(content);
                                assertEquals(ids.get(0), content.optString("objectID"));
                            }
                        });
                    }
                });
            }
        });
    }

    @Test
    public void partialUpdateObjectNoCreate() throws Exception {
        final String objectID = "unknown";
        final JSONObject partialObject = new JSONObject().put("city", "Paris");

        // Partial update on a nonexistent object with `createIfNotExists=false` should not create the object.
        index.partialUpdateObjectAsync(partialObject, objectID, false, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertNull(error);
                String taskID = content.optString("taskID");
                index.waitTaskAsync(taskID, new AssertCompletionHandler() {
                    @Override
                    public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                        assertNull(error);
                        index.getObjectAsync(objectID, new AssertCompletionHandler() {
                            @Override
                            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                assertNotNull(error);
                                assertEquals(404, error.getStatusCode());

                                // Partial update on a nonexistent object with `createIfNotExists=true` should create the object.
                                index.partialUpdateObjectAsync(partialObject, objectID, true, new AssertCompletionHandler() {
                                    @Override
                                    public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                        assertNull(error);
                                        String taskID = content.optString("taskID");
                                        index.waitTaskAsync(taskID, new AssertCompletionHandler() {
                                            @Override
                                            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                                assertNull(error);
                                                index.getObjectAsync(objectID, new AssertCompletionHandler() {
                                                    @Override
                                                    public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                                        assertNotNull(content);
                                                        assertEquals(objectID, content.optString("objectID"));
                                                    }
                                                });
                                            }
                                        });
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    @Test
    public void partialUpdateObjects() throws Exception {
        final JSONArray partialObjects = new JSONArray()
                .put(new JSONObject().put("objectID", ids.get(0)).put("city", "Paris"))
                .put(new JSONObject().put("objectID", ids.get(1)).put("city", "Berlin"));

        index.partialUpdateObjectsAsync(partialObjects, false, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertNull(error);
                String taskID = content.optString("taskID");
                index.waitTaskAsync(taskID, new AssertCompletionHandler() {
                    @Override
                    public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                        assertNull(error);
                        index.getObjectsAsync(ids, new AssertCompletionHandler() {
                            @Override
                            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                assertNotNull(content);
                                JSONArray results = content.optJSONArray("results");
                                assertNotNull(results);
                                assertEquals(2, results.length());
                                for (int i = 0; i < partialObjects.length(); ++i) {
                                    assertEquals(ids.get(i), results.optJSONObject(i).optString("objectID"));
                                    assertEquals(partialObjects.optJSONObject(i).optString("city"), results.optJSONObject(i).optString("city"));
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Test
    public void partialUpdateObjectsNoCreate() throws Exception {
        final List<String> newIds = Arrays.asList("unknown", "none");
        final JSONArray partialObjects = new JSONArray()
            .put(new JSONObject().put("objectID", newIds.get(0)).put("city", "Paris"))
            .put(new JSONObject().put("objectID", newIds.get(1)).put("city", "Berlin"));

        index.partialUpdateObjectsAsync(partialObjects, false, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertNull(error);
                String taskID = content.optString("taskID");
                index.waitTaskAsync(taskID, new AssertCompletionHandler() {
                    @Override
                    public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                        assertNull(error);
                        index.getObjectsAsync(newIds, new AssertCompletionHandler() {
                            @Override
                            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                // NOTE: A multiple get objects doesn't return an error for nonexistent objects,
                                // but simply returns `null` for the missing objects.
                                assertNotNull(content);
                                JSONArray results = content.optJSONArray("results");
                                assertNotNull(results);
                                assertEquals(2, results.length());
                                assertEquals(null, results.optJSONObject(0));
                                assertEquals(null, results.optJSONObject(1));

                                index.partialUpdateObjectsAsync(partialObjects, false, new AssertCompletionHandler() {
                                    @Override
                                    public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                        assertNull(error);
                                        String taskID = content.optString("taskID");
                                        index.waitTaskAsync(taskID, new AssertCompletionHandler() {
                                            @Override
                                            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                                assertNull(error);
                                                index.getObjectsAsync(newIds, new AssertCompletionHandler() {
                                                    @Override
                                                    public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                                                        assertNotNull(content);
                                                        JSONArray results = content.optJSONArray("results");
                                                        assertNotNull(results);
                                                        assertEquals(2, results.length());
                                                        for (int i = 0; i < partialObjects.length(); ++i) {
                                                            assertEquals(partialObjects.optJSONObject(i).optString("city"), results.optJSONObject(i).optString("city"));
                                                        }
                                                    }
                                                });
                                            }
                                        });
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    @Test
    public void searchForFacFacetValues() throws Exception {
        final JSONObject setSettingsTask = index.setSettings(new JSONObject()
                .put("attributesForFaceting", new JSONArray()
                        .put("searchable(series)")
                        .put("kind"))
        );

        final JSONObject addObjectsResult = index.addObjects(new JSONArray()
                .put(new JSONObject()
                        .put("objectID", "1")
                        .put("name", "Snoopy")
                        .put("kind", new JSONArray().put("dog").put("animal"))
                        .put("born", 1950)
                        .put("series", "Peanuts"))
                .put(new JSONObject()
                        .put("objectID", "2")
                        .put("name", "Woodstock")
                        .put("kind", new JSONArray().put("bird").put("animal"))
                        .put("born", 1960)
                        .put("series", "Peanuts"))
                .put(new JSONObject()
                        .put("objectID", "3")
                        .put("name", "Charlie Brown")
                        .put("kind", new JSONArray().put("human"))
                        .put("born", 1950)
                        .put("series", "Peanuts"))
                .put(new JSONObject()
                        .put("objectID", "4")
                        .put("name", "Hobbes")
                        .put("kind", new JSONArray().put("tiger").put("animal").put("teddy"))
                        .put("born", 1985)
                        .put("series", "Calvin & Hobbes"))
                .put(new JSONObject()
                        .put("objectID", "5")
                        .put("name", "Calvin")
                        .put("kind", new JSONArray().put("human"))
                        .put("born", 1985)
                        .put("series", "Calvin & Hobbes"))
        );

        index.waitTask(setSettingsTask.getString("taskID"));
        index.waitTask(addObjectsResult.getString("taskID"));

        index.searchForFacetValues("series", "Hobb", null, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertNull("There should be no error", error);
                final JSONArray facetHits = content.optJSONArray("facetHits");
                assertTrue("The response should have facetHits.", facetHits != null);
                try {
                    assertEquals("There should be one facet match.", 1, facetHits.length());
                    JSONObject result = facetHits.getJSONObject(0);
                    assertEquals("The serie should be Calvin & Hobbes.", "Calvin & Hobbes", result.getString("value"));
                    assertEquals("Two results should have matched.", 2, result.getInt("count"));
                } catch (JSONException e) {
                    fail(e.toString());
                }
            }
        });

        Query query = new Query()
                .setFacetFilters(new JSONArray().put("kind:animal"))
                .setNumericFilters(new JSONArray().put("born >= 1955"));
        index.searchForFacetValues("series", "Peanutz", query, new AssertCompletionHandler() {
            @Override
            public void doRequestCompleted(JSONObject content, AlgoliaException error) {
                assertNull("There should be no error", error);
                final JSONArray facetHits = content.optJSONArray("facetHits");
                assertTrue("The response should have facetHits.", facetHits != null);
                try {
                    assertEquals("There should be one facet match.", 1, facetHits.length());
                    JSONObject result = facetHits.getJSONObject(0);
                    assertEquals("The serie should be Peanuts.", "Peanuts", result.getString("value"));
                    assertEquals("Two results should have matched.", 1, result.getInt("count"));
                } catch (JSONException e) {
                    fail(e.getMessage());
                }
            }
        });
    }

    @Test
    public void retryUsingHostStatus() throws Exception {
        List<String> hostsArray = (List<String>) Whitebox.getInternalState(client, "readHosts");
        String randomHostName = getRandomString() + "-dsn.algolia.biz";
        final String nextHostName = hostsArray.get(1);

        // Given a first host that timeouts, randomized to ensure no system caching
        hostsArray.set(0, randomHostName);
        Whitebox.setInternalState(client, "readHosts", hostsArray);


        // Expect reachable hosts before any connection
        assertTrue("Hosts should be considered up before first connection.", client.isUpOrCouldBeRetried(randomHostName));
        assertTrue("Hosts should be considered up before first connection.", client.isUpOrCouldBeRetried(nextHostName));

        // Expect success after a failing host
        searchAsync();

        // Expect down host after failed connection, up host after successful connection
        assertFalse("A host that has failed recently should be considered down.", client.isUpOrCouldBeRetried(randomHostName));
        assertTrue("A host that has succeeded recently should be considered up.", client.isUpOrCouldBeRetried(nextHostName));

        hostsArray = (List<String>) Whitebox.getInternalState(client, "readHosts");
        randomHostName = getRandomString() + "-dsn.algolia.biz";

        // Given a short host delay and a first host that timeouts
        final int delay = 100;
        client.setHostDownDelay(delay);
        hostsArray.set(0, randomHostName);
        Whitebox.setInternalState(client, "readHosts", hostsArray);

        // Expect success after a failing host
        searchAsync();

        // Expect host to be up again after the delay has passed
        Thread.sleep(delay);
        assertTrue("A host that has failed should be considered up once the delay is over.", client.isUpOrCouldBeRetried(randomHostName));
    }

    private String getRandomString() {
        return UUID.randomUUID().toString();
    }
}
