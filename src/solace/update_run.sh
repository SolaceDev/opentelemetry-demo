#!/bin/bash

function waitAndConfigure() {
    while [ "$(curl -skL -X GET -w '%{http_code}' -u admin:admin 0.0.0.0:8080/SEMP/v2/monitor/msgVpns/default/queues -o /dev/null)" -ne 200 ]; do
        echo "Waiting on management..."
        sleep 2
    done

    while [ "$(curl -skL -X GET -w '%{http_code}' 0.0.0.0:5550/health-check/direct-active -o /dev/null)" -ne 200 ]; do
        echo "Waiting on messaging..."
        sleep 2
    done


    broker="http://0.0.0.0:8080"
    username="admin"
    password="admin"

    sempPath="$broker/SEMP/v2/config"
    vpn="default"
    vpnPath="$sempPath/msgVpns/$vpn"
    profile="demo"
    profilePath="$vpnPath/telemetryProfiles"

    curlCmd="curl -u $username:$password"

    profileData="{
        \"msgVpnName\":\"$vpn\",
        \"receiverAclConnectDefaultAction\":\"allow\",
        \"receiverEnabled\":true,
        \"telemetryProfileName\":\"$profile\",
        \"traceEnabled\":true
    }"

    $curlCmd -X POST -H "Content-Type: application/json" "$profilePath" -d "$profileData"

    filter="all"
    filterPath="$profilePath/$profile/traceFilters"

    filterData="{
        \"enabled\":true,
        \"msgVpnName\":\"$vpn\",
        \"telemetryProfileName\":\"$profile\",
        \"traceFilterName\":\"$filter\"
    }"

    $curlCmd -X POST -H "Content-Type: application/json" "$filterPath" -d "$filterData"

    subscription=">"
    subscriptionPath="$filterPath/$filter/subscriptions"

    subscriptionData="{
        \"subscription\":\"$subscription\",
        \"subscriptionSyntax\":\"smf\",
        \"msgVpnName\":\"$vpn\",
        \"telemetryProfileName\":\"$profile\",
        \"traceFilterName\":\"$filter\"
    }"

    $curlCmd -X POST -H "Content-Type: application/json" $subscriptionPath -d "$subscriptionData"

    $curlCmd -X PATCH -H "Content-Type: application/json" $vpnPath -d '{"authenticationBasicType":"internal"}'

    clientUsernamePath="$vpnPath/clientUsernames"
    $curlCmd -X PATCH -H "Content-Type: application/json" "$clientUsernamePath/default" -d '{"password":"default"}'

    clientUsername="trace"
    clientUsernameData="{
        \"aclProfileName\":\"#telemetry-$profile\",
        \"clientProfileName\":\"#telemetry-$profile\",
        \"clientUsername\":\"$clientUsername\",
        \"enabled\":true,
        \"msgVpnName\":\"$vpn\",
        \"password\":\"$clientUsername\"
    }"

    $curlCmd -X POST -H "Content-Type: application/json" $clientUsernamePath -d "$clientUsernameData"

    # Create endpoints

    queuePath="$vpnPath/queues"

    queueSubscriptionName=">"
    queueSubscriptionData="{
        \"subscriptionTopic\": \"$queueSubscriptionName\"
    }"

    # First queue for the accounting service

    accountingQueueName="accounting-orders"
    accountingQueueData="{
        \"egressEnabled\": true,
        \"ingressEnabled\": true,
        \"permission\": \"consume\",
        \"queueName\": \"$accountingQueueName\"
    }"

    $curlCmd -X POST -H "Content-Type: application/json" $queuePath -d "$accountingQueueData"

    accountingQueueSubscriptionPath="$queuePath/$accountingQueueName/subscriptions"
    $curlCmd -X POST -H "Content-Type: application/json" $accountingQueueSubscriptionPath -d "$queueSubscriptionData"

    # second queue for fraud detection

    fraudDetectionQueueName="fraud-detection-orders"
    fraudDetectionQueueData="{
        \"egressEnabled\": true,
        \"ingressEnabled\": true,
        \"permission\": \"consume\",
        \"queueName\": \"$fraudDetectionQueueName\"
    }"

    queuePath="$vpnPath/queues"

    $curlCmd -X POST -H "Content-Type: application/json" $queuePath -d "$fraudDetectionQueueData"

    fraudDetectionQueueSubscriptionPath="$queuePath/$fraudDetectionQueueName/subscriptions"
    $curlCmd -X POST -H "Content-Type: application/json" $fraudDetectionQueueSubscriptionPath -d "$queueSubscriptionData"
}


waitAndConfigure &

# Note this does need to be an exec
exec /usr/sbin/boot.sh