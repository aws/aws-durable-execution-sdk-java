// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Proxy;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
public class TestingSdkCompatibilityProbe {
  public static void main(String[] args) {
    var unused=(DurableExecutionClient)Proxy.newProxyInstance(DurableExecutionClient.class.getClassLoader(),
      new Class<?>[]{DurableExecutionClient.class}, (p,m,a)->{throw new AssertionError("unexpected client call");});
    var config=DurableConfig.builder().withDurableExecutionClient(unused).build();
    var result=LocalDurableTestRunner.create(String.class,(input,context)->input,config).runUntilComplete("ok");
    if(result.getStatus()!=ExecutionStatus.SUCCEEDED || !"ok".equals(result.getResult(String.class)))throw new AssertionError(result);
    System.out.println("PASS testing-sdk on released core");
  }
}
