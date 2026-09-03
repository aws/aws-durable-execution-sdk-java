// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class LambdaEventSerDesTest {
    private static final String SQS_EVENT_JSON = """
            {
              "Records": [{
                "messageId": "message-1",
                "receiptHandle": "receipt-1",
                "body": "hello from sqs",
                "attributes": {"ApproximateReceiveCount": "1"},
                "messageAttributes": {},
                "md5OfBody": "098f6bcd4621d373cade4e832627b4f6",
                "eventSource": "aws:sqs",
                "eventSourceARN": "arn:aws:sqs:us-east-1:123456789012:orders",
                "awsRegion": "us-east-1"
              }]
            }
            """;

    private static final String S3_EVENT_JSON = """
            {
              "Records": [{
                "awsRegion": "us-east-1",
                "eventName": "ObjectCreated:Put",
                "eventSource": "aws:s3",
                "eventTime": "2026-06-18T16:53:11.000Z",
                "eventVersion": "2.1",
                "requestParameters": {"sourceIPAddress": "127.0.0.1"},
                "responseElements": {
                  "x-amz-id-2": "extended-request-id",
                  "x-amz-request-id": "request-id"
                },
                "s3": {
                  "configurationId": "configuration-id",
                  "bucket": {
                    "name": "orders",
                    "ownerIdentity": {"principalId": "principal-id"},
                    "arn": "arn:aws:s3:::orders"
                  },
                  "object": {
                    "key": "order.json",
                    "size": 42,
                    "eTag": "etag",
                    "sequencer": "sequencer"
                  },
                  "s3SchemaVersion": "1.0"
                },
                "userIdentity": {"principalId": "principal-id"}
              }]
            }
            """;

    private static final String SNS_EVENT_JSON = """
            {
              "Records": [{
                "EventSource": "aws:sns",
                "EventVersion": "1.0",
                "EventSubscriptionArn": "arn:aws:sns:us-east-1:123456789012:orders:subscription",
                "Sns": {
                  "Type": "Notification",
                  "MessageId": "message-1",
                  "TopicArn": "arn:aws:sns:us-east-1:123456789012:orders",
                  "Subject": "order",
                  "Message": "hello from sns",
                  "Timestamp": "2026-06-18T16:53:11.000Z",
                  "SignatureVersion": "1",
                  "Signature": "signature",
                  "SigningCertUrl": "https://sns.us-east-1.amazonaws.com/cert.pem",
                  "UnsubscribeUrl": "https://sns.us-east-1.amazonaws.com/unsubscribe",
                  "MessageAttributes": {}
                }
              }]
            }
            """;

    private final LambdaEventSerDes serDes = new LambdaEventSerDes();

    @Test
    void deserializesSqsEventUsingLambdaRuntimePropertyNames() {
        var event = serDes.deserialize(SQS_EVENT_JSON, TypeToken.get(SQSEvent.class));

        assertNotNull(event.getRecords());
        assertEquals(1, event.getRecords().size());
        var message = event.getRecords().get(0);
        assertEquals("message-1", message.getMessageId());
        assertEquals("hello from sqs", message.getBody());
        assertEquals("arn:aws:sqs:us-east-1:123456789012:orders", message.getEventSourceArn());
    }

    @Test
    void deserializesSnsEventUsingLambdaRuntimePropertyNames() {
        var event = serDes.deserialize(SNS_EVENT_JSON, TypeToken.get(SNSEvent.class));

        assertNotNull(event.getRecords());
        assertEquals(1, event.getRecords().size());
        var record = event.getRecords().get(0);
        assertNotNull(record.getSNS());
        assertEquals("hello from sns", record.getSNS().getMessage());
        assertEquals(
                "arn:aws:sns:us-east-1:123456789012:orders", record.getSNS().getTopicArn());
    }

    @Test
    void serializesSqsEventUsingLambdaRuntimePropertyNames() {
        var event = serDes.deserialize(SQS_EVENT_JSON, TypeToken.get(SQSEvent.class));

        var json = serDes.serialize(event);

        assertTrue(json.contains("\"Records\""));
        assertTrue(json.contains("\"eventSourceARN\""));
        assertFalse(json.contains("\"records\""));
        assertFalse(json.contains("\"eventSourceArn\""));

        var roundTripped = serDes.deserialize(json, TypeToken.get(SQSEvent.class));
        assertEquals("hello from sqs", roundTripped.getRecords().get(0).getBody());
    }

    @Test
    void roundTripsS3EventUsingLambdaRuntimeSerializer() {
        var event = serDes.deserialize(S3_EVENT_JSON, TypeToken.get(S3Event.class));

        assertNotNull(event.getRecords());
        assertEquals(1, event.getRecords().size());
        assertEquals("orders", event.getRecords().get(0).getS3().getBucket().getName());
        assertEquals("order.json", event.getRecords().get(0).getS3().getObject().getKey());

        var json = serDes.serialize(event);
        var roundTripped = serDes.deserialize(json, TypeToken.get(S3Event.class));

        assertTrue(json.contains("\"Records\""));
        assertEquals(
                "orders", roundTripped.getRecords().get(0).getS3().getBucket().getName());
    }

    @Test
    void delegatesNonEventAndGenericTypes() {
        var delegate = new TrackingSerDes();
        var eventSerDes = new LambdaEventSerDes(delegate);

        var json = eventSerDes.serialize(List.of("one", "two"));
        var result = eventSerDes.deserialize(json, new TypeToken<List<String>>() {});

        assertEquals(List.of("one", "two"), result);
        assertEquals(1, delegate.serializeCount);
        assertEquals(1, delegate.deserializeCount);
    }

    @Test
    void handlesNullValues() {
        assertNull(serDes.serialize(null));
        assertNull(serDes.deserialize(null, TypeToken.get(SQSEvent.class)));
    }

    @Test
    void wrapsEventDeserializationFailures() {
        var exception = assertThrows(
                SerDesException.class, () -> serDes.deserialize("{\"Records\":[", TypeToken.get(SQSEvent.class)));

        assertTrue(exception.getMessage().contains("Deserialization failed for Lambda event type"));
        assertTrue(exception.getMessage().contains(SQSEvent.class.getName()));
        assertNotNull(exception.getCause());
    }

    @Test
    void rejectsNullDelegate() {
        var exception = assertThrows(NullPointerException.class, () -> new LambdaEventSerDes(null));

        assertEquals("Delegate SerDes cannot be null", exception.getMessage());
    }

    private static final class TrackingSerDes implements SerDes {
        private final JacksonSerDes delegate = new JacksonSerDes();
        private int serializeCount;
        private int deserializeCount;

        @Override
        public String serialize(Object value) {
            serializeCount++;
            return delegate.serialize(value);
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            deserializeCount++;
            return delegate.deserialize(data, typeToken);
        }
    }
}
