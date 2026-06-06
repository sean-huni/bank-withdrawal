#!/bin/sh
# LocalStack ready-hook: create the SNS topic the application publishes withdrawal events to.
awslocal sns create-topic --name bank-withdrawal-events
