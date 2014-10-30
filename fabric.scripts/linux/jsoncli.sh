#!/bin/sh
#. /opt/edgware-0.4.0/env.sh
FABRIC_LIBS=$(echo $FABRIC_HOME/lib/db-derby/lib/*.jar | tr ' ' ':')
FABRIC_LIBS=$FABRIC_LIBS:$(echo $FABRIC_HOME/lib/fabric/*.jar | tr ' ' ':')
FABRIC_LIBS=$FABRIC_LIBS:$(echo $FABRIC_HOME/lib/gaiandb/lib/*.jar | tr ' ' ':')
FABRIC_LIBS=$FABRIC_LIBS:$(echo $FABRIC_HOME/lib/oslib/*.jar | tr ' ' ':')

 java -Dconfig=$FABRIC_HOME/bin/config/JsonCLI.properties -Dfabric.config=$FABRIC_HOME/osgi/configuration/fabricConfig_default.properties -Djava.util.logging.config.file=$FABRIC_HOME/osgi/configuration/logging.properties -cp $FABRIC_LIBS fabric.tools.json.JsonCLI $1

