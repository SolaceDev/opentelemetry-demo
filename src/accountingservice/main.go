// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package main

//go:generate go install google.golang.org/protobuf/cmd/protoc-gen-go
//go:generate go install google.golang.org/grpc/cmd/protoc-gen-go-grpc
//go:generate protoc --go_out=./ --go-grpc_out=./ --proto_path=../../pb ../../pb/demo.proto

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"sync"
	"time"

	"github.com/sirupsen/logrus"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	sdkresource "go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.17.0"
	"go.opentelemetry.io/otel/trace"
	"solace.dev/go/messaging"
	"solace.dev/go/messaging/pkg/solace/config"
	"solace.dev/go/messaging/pkg/solace/message"
	solaceresource "solace.dev/go/messaging/pkg/solace/resource"

	"github.com/open-telemetry/opentelemetry-demo/src/accountingservice/kafka"
)

var log *logrus.Logger
var tracer trace.Tracer
var resource *sdkresource.Resource
var initResourcesOnce sync.Once

func init() {
	log = logrus.New()
	log.Level = logrus.DebugLevel
	log.Formatter = &logrus.JSONFormatter{
		FieldMap: logrus.FieldMap{
			logrus.FieldKeyTime:  "timestamp",
			logrus.FieldKeyLevel: "severity",
			logrus.FieldKeyMsg:   "message",
		},
		TimestampFormat: time.RFC3339Nano,
	}
	log.Out = os.Stdout
}

func initResource() *sdkresource.Resource {
	initResourcesOnce.Do(func() {
		extraResources, _ := sdkresource.New(
			context.Background(),
			sdkresource.WithOS(),
			sdkresource.WithProcess(),
			sdkresource.WithContainer(),
			sdkresource.WithHost(),
		)
		resource, _ = sdkresource.Merge(
			sdkresource.Default(),
			extraResources,
		)
	})
	return resource
}

func initTracerProvider() (*sdktrace.TracerProvider, error) {
	ctx := context.Background()

	exporter, err := otlptracegrpc.New(ctx)
	if err != nil {
		return nil, err
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(initResource()),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	tracer = tp.Tracer("accountingservice")
	return tp, nil
}

func main() {
	tp, err := initTracerProvider()
	if err != nil {
		log.Fatal(err)
	}
	defer func() {
		if err := tp.Shutdown(context.Background()); err != nil {
			log.Printf("Error shutting down tracer provider: %v", err)
		}
	}()

	var brokers string
	mustMapEnv(&brokers, "KAFKA_SERVICE_ADDR")
	var solaceBroker string
	mustMapEnv(&solaceBroker, "SOLACE_SERVICE_ADDR")

	brokerList := strings.Split(brokers, ",")
	log.Printf("Kafka brokers: %s", strings.Join(brokerList, ", "))

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)

	// Run solace receiver in another goroutine to not get blocked by kafka
	go func() {
		messagingService, err := messaging.NewMessagingServiceBuilder().FromConfigurationProvider(config.ServicePropertyMap{
			config.TransportLayerPropertyHost:                solaceBroker,
			config.ServicePropertyVPNName:                    "default",
			config.AuthenticationPropertySchemeBasicUserName: "default",
			config.AuthenticationPropertySchemeBasicPassword: "default",
		}).Build()
		if err != nil {
			log.Fatal(err)
		}
		err = messagingService.Connect()
		if err != nil {
			log.Fatal(err)
		}
		receiver, err := messagingService.CreatePersistentMessageReceiverBuilder().WithMessageAutoAcknowledgement().Build(solaceresource.QueueDurableExclusive("accounting-orders"))
		if err != nil {
			log.Fatal(err)
		}

		receiver.ReceiveAsync(func(inboundMessage message.InboundMessage) {
			type tracingMessageSupport interface {
				GetTransportTraceContext() (traceID [16]byte, spanID [8]byte, sampled bool, traceState string, ok bool)
			}
			msgTracingSupport, ok := inboundMessage.(tracingMessageSupport)
			if ok {
				traceID, spanID, _, _, ok := msgTracingSupport.GetTransportTraceContext()
				if !ok {
					log.Error("Message did not have trace ID")
				}

				ctx := trace.ContextWithSpanContext(context.Background(), trace.NewSpanContext(trace.SpanContextConfig{
					TraceID:    traceID,
					SpanID:     spanID,
					Remote:     true,
					TraceFlags: trace.FlagsSampled,
				}))

				_, span := tracer.Start(ctx, "solace go api receive", trace.WithSpanKind(trace.SpanKindConsumer), trace.WithAttributes(
					semconv.MessagingSystem("solace"),
					semconv.MessagingDestinationKindTopic,
					semconv.MessagingDestinationName(inboundMessage.GetDestinationName()),
					semconv.MessagingOperationReceive,
				))

				// do stuff with message

				span.End()
			}
		})

		err = receiver.Start()
		if err != nil {
			log.Fatal(err)
		}
	}()

	defer cancel()
	if err := kafka.StartConsumerGroup(ctx, brokerList, log); err != nil {
		log.Fatal(err)
	}

	<-ctx.Done()
}

func mustMapEnv(target *string, envKey string) {
	v := os.Getenv(envKey)
	if v == "" {
		panic(fmt.Sprintf("environment variable %q not set", envKey))
	}
	*target = v
}
