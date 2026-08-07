/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.enhanced.dynamodb.functionaltests;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import com.amazonaws.services.dynamodbv2.local.main.ServerRunner;
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Starts in-process DynamoDB Local for integration tests in this module.
 */
abstract class ProjectionLocalDynamoDbTestBase {

    private static DynamoDBProxyServer server;
    private static int port;
    protected static DynamoDbClient client;

    @BeforeAll
    public static void startLocalDynamoDb() throws Exception {
        port = freePort();
        server = ServerRunner.createServerFromCommandLineArgs(
            new String[] {"-inMemory", "-port", Integer.toString(port)});
        server.start();
        client = DynamoDbClient.builder()
                               .endpointOverride(URI.create("http://localhost:" + port))
                               .region(Region.US_EAST_1)
                               .credentialsProvider(StaticCredentialsProvider.create(
                                   AwsBasicCredentials.create("dummykey", "dummysecret")))
                               .build();
    }

    @AfterAll
    public static void stopLocalDynamoDb() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (client != null) {
            client.close();
        }
    }

    protected static void recreateTable(String tableName,
                                        List<KeySchemaElement> keySchema,
                                        List<AttributeDefinition> attributeDefinitions) {
        try {
            client.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
        } catch (ResourceNotFoundException ignored) {
        }
        client.createTable(CreateTableRequest.builder()
                                             .tableName(tableName)
                                             .billingMode(BillingMode.PAY_PER_REQUEST)
                                             .keySchema(keySchema)
                                             .attributeDefinitions(attributeDefinitions)
                                             .build());
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        int p = socket.getLocalPort();
        socket.close();
        return p;
    }
}
