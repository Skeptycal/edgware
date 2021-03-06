/*
 * (C) Copyright IBM Corp. 2014
 *
 * LICENSE: Eclipse Public License v1.0
 * http://www.eclipse.org/legal/epl-v10.html
 */

package fabric.services.jsonclient.articles;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import fabric.ServiceDescriptor;
import fabric.SystemDescriptor;
import fabric.core.json.JSON;
import fabric.core.json.JSONArray;
import fabric.registry.FabricRegistry;
import fabric.registry.QueryScope;
import fabric.registry.Service;
import fabric.registry.ServiceFactory;
import fabric.registry.System;
import fabric.registry.SystemFactory;
import fabric.registry.TaskService;
import fabric.registry.TaskServiceFactory;
import fabric.registry.Type;
import fabric.registry.TypeFactory;
import fabric.services.jsonclient.JSONAdapter;
import fabric.services.jsonclient.utilities.AdapterConstants;
import fabric.services.jsonclient.utilities.AdapterStatus;
import fabric.services.systems.RuntimeManager;
import fabric.services.systems.RuntimeStatus;

/**
 * Class that handles JSON operations that deal with Systems.
 */
public class Systems extends Article {

    /** Copyright notice. */
    public static final String copyrightNotice = "(C) Copyright IBM Corp. 2014";

    /*
     * Class fields
     */

    /*
     * Class static methods
     */

    /**
     * Inserts a System into the Registry.
     *
     * @param op
     *            The full JSON operation object.
     *
     * @param correlId
     *            The correlation ID of the request.
     *
     * @return A JSON status object.
     */
    public static JSON register(final JSON op, String correlId, Object clientID) {

        AdapterStatus status = new AdapterStatus(correlId);

        try {

            String systemName = op.getString(AdapterConstants.FIELD_ID);
            String systemType = op.getString(AdapterConstants.FIELD_TYPE);

            String user = op.getString(AdapterConstants.FIELD_USER);

            if (systemName == null || systemType == null) {

                status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_REGISTER,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FIELD_ERROR, correlId);

            } else {

                SystemDescriptor systemDescriptor = new SystemDescriptor(systemName);

                if (!(user == null)) {
                    // TODO User logic
                }

                /* Lookup the list of service types associated with the system type */
                String[] serviceTypes = getServiceTypes(systemType);

                /* If there are any... */
                if (serviceTypes != null) {

                    /* Create the corresponding service instances in the Registry */
                    status = createServices(serviceTypes, systemDescriptor.platform(), systemDescriptor.system(),
                            correlId);

                    if (status != null && status.isOK()) {

                        JSON attr = op.getJSON(AdapterConstants.FIELD_ATTRIBUTES);
                        attr = (attr != null) ? attr : new JSON();

                        /* Set the default client ID if required */
                        if ("true".equals(attr.getString("autoStart"))) {
                            if (attr.getString("clientID") == null) {
                                String id = (clientID != null) ? clientID.toString() : "$unknown";
                                attr.putString("clientID", id);
                            }
                        }

                        /* Insert the system into the Registry */
                        SystemFactory systemFactory = FabricRegistry.getSystemFactory();
                        System system = systemFactory.createSystem(systemDescriptor.platform(), // platform ID
                                systemDescriptor.system(), // service ID
                                systemType, // system type
                                null, // credentials
                                "DEPLOYED", // readiness
                                AdapterConstants.STATE_AVAILABLE, // availability,
                                0, // latitude,
                                0, // longitude,
                                0, // altitude,
                                0, // bearing,
                                0, // velocity,
                                op.getString(AdapterConstants.FIELD_DESCRIPTION), // system description
                                (attr != null) ? attr.toString() : null, null); // attributes, attributes URI;
                        boolean success = systemFactory.save(system);

                        if (!success) {

                            status = new AdapterStatus(AdapterConstants.ERROR_ACTION,
                                    AdapterConstants.OP_CODE_REGISTER, AdapterConstants.ARTICLE_SYSTEM,
                                    "Insert/update of system into the Registry failed", correlId);

                        }
                    }

                } else {

                    status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_REGISTER,
                            AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_UNRECOGNIZED_TYPE, correlId);

                }
            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_REGISTER,
                    AdapterConstants.ARTICLE_SYSTEM, message, correlId);

        }

        return status.toJsonObject();
    }

    /**
     * Creates the Registry entries for the services corresponding to a system.
     *
     * @param serviceTypes
     *            the list of service types to register.
     *
     * @param platformId
     *            the system's platform ID.
     *
     * @param systemId
     *            the system's service ID.
     *
     * @param correlId
     *            the operation correlation ID.
     *
     * @return the status of the call.
     */
    private static AdapterStatus createServices(String[] serviceTypes, String platformId, String systemId,
            String correlId) {

        AdapterStatus status = new AdapterStatus(correlId);
        TypeFactory typeFactory = FabricRegistry.getTypeFactory(QueryScope.LOCAL);
        Type serviceTypeRecord = null;

        /* For each service... */
        for (String nextServiceType : serviceTypes) {

            String[] serviceParts = nextServiceType.split(":");
            String name = null;
            String mode = "";
            String type = "";
            switch (serviceParts.length) {
                case 3:
                    name = serviceParts[2];
                case 2:
                    mode = serviceParts[1];
                case 1:
                    type = serviceParts[0];
                    name = (name == null) ? type : name;
                    break;
            }

            /* Lookup the service type in the Registry */
            serviceTypeRecord = typeFactory.getServiceType(type);

            /* If we didn't find it... */
            if (serviceTypeRecord == null) {
                status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_REGISTER,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_UNRECOGNIZED_TYPE, correlId);
                break;
            }

            /* Get the system's attributes */
            JSON attr = null;
            try {
                String attrString = serviceTypeRecord.getAttributes();
                attr = new JSON((attrString != null) ? attrString : "{}");
            } catch (Exception e) {
                status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_REGISTER,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_BAD_ATTRIBUTE_JSON, correlId);
                break;
            }

            /* If the mode was not specified with the system type... */
            if (mode.equals("")) {
                /* Get the mode of the service from the service type */
                mode = attr.getString("mode");
            }

            /* Used to hold the updated mode name (known as the "direction" in the research Fabric Registry schema) */
            String direction = null;

            switch ((mode != null) ? mode : "") {

                case AdapterConstants.MODE_INPUT:

                    direction = AdapterConstants.DIRECTION_INPUT;
                    break;

                case AdapterConstants.MODE_OUTPUT:

                    direction = AdapterConstants.DIRECTION_OUTPUT;
                    break;

                case AdapterConstants.MODE_NOTIFY:

                    direction = AdapterConstants.DIRECTION_NOTIFICATION;
                    break;

                case AdapterConstants.MODE_LISTEN:

                    direction = AdapterConstants.DIRECTION_ONE_WAY;
                    break;

                case AdapterConstants.MODE_SOLICIT:

                    direction = AdapterConstants.DIRECTION_SOLICIT_RESPONSE;
                    break;

                case AdapterConstants.MODE_RESPONSE:

                    direction = AdapterConstants.DIRECTION_REQUEST_RESPONSE;
                    break;

                default:

                    break;
            }

            status = createService(platformId, systemId, name, type, direction, attr, correlId);

            if (status.isOK()) {

                status = createTaskService(platformId, systemId, name, correlId);

            }
        }

        return status;
    }

    /**
     * Creates the Registry entries for a services.
     *
     * @param platformId
     *            the system's platform ID.
     *
     * @param systemId
     *            the system ID.
     *
     * @param serviceId
     *            the ID of the service.
     *
     * @param serviceTypeId
     *            the type of the service.
     *
     * @param mode
     *            the mode of the service.
     *
     * @param correlId
     *            the operation correlation ID.
     *
     * @return the status of the call.
     */
    private static AdapterStatus createService(String platformId, String systemId, String serviceId,
            String serviceTypeId, String mode, JSON attr, String correlId) {

        AdapterStatus status = new AdapterStatus(correlId);

        try {

            /* Create the Registry entry */
            ServiceFactory systemFactory = FabricRegistry.getServiceFactory();
            Service service = systemFactory.createService( //
                    platformId, //
                    systemId, //
                    serviceId, //
                    serviceTypeId, //
                    mode, //
                    null, // credentials
                    AdapterConstants.STATE_AVAILABLE, //
                    null, // description
                    (attr != null) ? attr.toString() : null, // attributes
                            null); // attributes URI
            boolean success = systemFactory.save(service);

            if (!success) {

                status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_REGISTER,
                        AdapterConstants.ARTICLE_SYSTEM, "Insert/update of system into the Registry failed", correlId);

            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_REGISTER,
                    AdapterConstants.ARTICLE_SYSTEM, message);

        }

        return status;
    }

    /**
     * Creates an entry for a service in the Registry's task services table.
     *
     * @param platformId
     *            the system's platform ID.
     *
     * @param systemId
     *            the system's service ID.
     *
     * @param serviceId
     *            the ID of the service.
     *
     * @param correlId
     *            the operation correlation ID.
     *
     * @return the status of the call.
     */
    private static AdapterStatus createTaskService(String platformId, String systemId, String serviceId, String correlId) {

        AdapterStatus status = new AdapterStatus(correlId);

        try {

            /* Create the Registry entry */
            TaskServiceFactory tsf = FabricRegistry.getTaskServiceFactory();
            TaskService ts = tsf.createTaskService("$def", platformId, systemId, serviceId);
            boolean success = tsf.save(ts);

            if (!success) {

                status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_REGISTER,
                        AdapterConstants.ARTICLE_SYSTEM, "Insert/update of service into the Registry failed", correlId);

            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_REGISTER,
                    AdapterConstants.ARTICLE_SYSTEM, message);

        }

        return status;
    }

    /**
     * Gets the list of service types associated with a system type.
     *
     * @param systemType
     *            the system type.
     *
     * @return the corresponding list of service types.
     */
    private static String[] getServiceTypes(String systemType) {

        String[] serviceTypes = null;

        /* Lookup the system type in the Registry */
        TypeFactory typeFactory = FabricRegistry.getTypeFactory(QueryScope.LOCAL);
        Type type = typeFactory.getSystemType(systemType);

        /* If we found it... */
        if (type != null) {

            /* Unpack the list of service types */
            String attrString = type.getAttributes();
            JSON attr = null;
            try {
                attr = new JSON((attrString != null) ? attrString : "{}");
            } catch (Exception e) {
                attr = new JSON();
            }
            String serviceTypeList = attr.getString("serviceTypes");
            serviceTypes = serviceTypeList.split(",");

        }

        return serviceTypes;
    }

    /**
     * Deletes a System from the Registry.
     *
     * @param systemName
     *            The ID of the System to be deleted.
     *
     * @param correlId
     *            The correlation ID for the status message.
     *
     * @return The status of the call.
     */
    public static JSON deregister(String systemName, String correlId) {

        AdapterStatus status = new AdapterStatus(correlId);

        if (systemName == null) {

            status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_DEREGISTER,
                    AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FIELD_ERROR, correlId);

        } else {

            SystemDescriptor systemDescriptor = new SystemDescriptor(systemName);

            /* Remove the system from the Registry */
            SystemFactory systemFactory = FabricRegistry.getSystemFactory(QueryScope.LOCAL);
            System system = systemFactory.getSystemsById(systemDescriptor.platform(), systemDescriptor.system());
            boolean complete = systemFactory.delete(system);

            /* If it failed... */
            if (complete == false) {

                status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_DEREGISTER,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FAILED_DELETE, correlId);

            }
        }

        return status.toJsonObject();
    }

    /**
     * Returns the result of a Systems query in a JSON Object.
     *
     * @param op
     *            The full JSON operation object.
     *
     * @param correlId
     *            The correlation ID of the request.
     *
     * @return the query result JSON Object.
     */
    public static JSON query(final JSON op, final String correlId) {

        JSON systemsQueryResult = new JSON();
        AdapterStatus status = new AdapterStatus(correlId);
        List<JSON> jsonList = new ArrayList<JSON>();

        try {

            SystemFactory systemFactory = FabricRegistry.getSystemFactory();

            String querySQL = generatePredicate(AdapterConstants.FIELD_PLATFORM, "PLATFORM_ID", QUERY_TYPE
                    | QUERY_ATTRIBUTES, op);

            if (querySQL == null) {

                status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_QUERY,
                        AdapterConstants.ARTICLE_SYSTEM_TYPE, AdapterConstants.STATUS_MSG_BAD_SQL, correlId);
                systemsQueryResult = status.toJsonObject();

            } else {

                /* Lookup the system list in the Registry */

                System[] resultArray = null;

                if ("".equals(querySQL)) {
                    resultArray = systemFactory.getAll();
                } else {
                    resultArray = systemFactory.getSystems(querySQL);
                }

                /* Generate the response object */

                systemsQueryResult.putString(AdapterConstants.FIELD_OPERATION,
                        AdapterConstants.OP_QUERY_RESPONSE_SYSTEMS);
                systemsQueryResult.putString(AdapterConstants.FIELD_CORRELATION_ID, correlId);

                /* For each system... */
                for (int i = 0; i < resultArray.length; i++) {

                    JSON system = new JSON();
                    system.putString(AdapterConstants.FIELD_ID, resultArray[i].getId());
                    system.putString(AdapterConstants.FIELD_TYPE, resultArray[i].getTypeId());
                    system.putString(AdapterConstants.FIELD_PLATFORM, resultArray[i].getPlatformId());
                    addOptionalFields(system, resultArray[i].getDescription(), resultArray[i].getAttributes());
                    jsonList.add(system);

                }
                systemsQueryResult.putArray(AdapterConstants.FIELD_SYSTEMS, jsonList);
            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_QUERY,
                    AdapterConstants.ARTICLE_SYSTEM, message, correlId);
            systemsQueryResult = status.toJsonObject();

        }

        return systemsQueryResult;
    }

    /**
     * Changes the state of the system. Changes running to stopped, or stopped to running.
     *
     * @param op
     *            the full JSON operation object.
     *
     * @param client
     *            adapter-specific ID of the client, used to target messages sent to the client.
     *
     * @param runtimeManager
     *            the runtime manager instance responsible for managing this system instance.
     *
     * @param adapterProxy
     *            the name of the class implementing the system adapter proxy for the JSON Fabric client.
     *
     * @param correlId
     *            The correlation ID of the request.
     *
     * @return A JSON status object.
     */
    public static JSON changeState(JSON op, Object client, RuntimeManager runtimeManager, String adapterProxy,
            String correlId) {

        JSON response = null;
        AdapterStatus status = new AdapterStatus(correlId);

        try {

            String id = op.getString(AdapterConstants.FIELD_ID);
            String state = op.getString(AdapterConstants.FIELD_STATE);

            if (id == null || state == null) {

                status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_STATE,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FIELD_ERROR, correlId);

            } else {

                SystemDescriptor systemDescriptor = new SystemDescriptor(id);

                switch (state) {

                    case AdapterConstants.STATE_RUNNING:

                        /* Start the system */
                        RuntimeStatus startStatus = runtimeManager.start(systemDescriptor, client, adapterProxy);

                        if (startStatus.getStatus() != RuntimeStatus.Status.OK) {
                            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_STATE,
                                    AdapterConstants.ARTICLE_SYSTEM, startStatus.getStatus() + ": "
                                            + startStatus.getMessage(), correlId);
                        }

                        break;

                    case AdapterConstants.STATE_STOPPED:

                        /* Stop the system */
                        RuntimeStatus stopStatus = runtimeManager.stop(systemDescriptor);

                        if (stopStatus.getStatus() != RuntimeStatus.Status.OK) {
                            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_STATE,
                                    AdapterConstants.ARTICLE_SYSTEM, stopStatus.getStatus() + ": "
                                            + stopStatus.getMessage(), correlId);
                        }

                        break;

                    default:

                        status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_STATE,
                                AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_BAD_STATE, correlId);
                        // response = status.getJson(correlId);
                        break;

                }
            }
        } catch (Exception e) {
            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_STATE,
                    AdapterConstants.ARTICLE_SYSTEM, message, correlId);
        }

        response = status.toJsonObject();
        return response;
    }

    /**
     * Handles a request in a request/response operation.
     *
     * @param op
     *            the full JSON operation object.
     *
     * @param runtimeManager
     *            the runtime manager instance responsible for managing this system instance.
     *
     * @param correlId
     *            The correlation ID of the request.
     *
     * @return A JSON status object.
     */
    public static JSON request(JSON op, RuntimeManager runtimeManager, String correlId) {

        JSON response = null;
        AdapterStatus status = new AdapterStatus(correlId);

        try {

            JSONArray sendToList = op.getJSONArray(AdapterConstants.FIELD_REQUEST_RESPONSE);
            String respondTo = op.getString(AdapterConstants.FIELD_SOLICIT_RESPONSE);
            String msg = op.getJSON(AdapterConstants.FIELD_MESSAGE).toString();
            String encoding = op.getString(AdapterConstants.FIELD_ENCODING);

            if (sendToList == null || respondTo == null || msg == null || correlId == null) {

                status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_REQUEST,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FIELD_ERROR, correlId);

            } else {

                if (encoding == null) {
                    encoding = AdapterConstants.FIELD_VALUE_ENCODING_ASCII;
                }

                ServiceDescriptor respondToDescriptor = new ServiceDescriptor(respondTo);

                /* For each target service... */
                for (Object sendTo : sendToList) {

                    ServiceDescriptor sendToDescriptor = new ServiceDescriptor(sendTo.toString());

                    /* Start the request */
                    RuntimeStatus requestStatus = runtimeManager.request(sendToDescriptor, respondToDescriptor, msg,
                            encoding, correlId);

                    if (requestStatus.getStatus() != RuntimeStatus.Status.OK) {
                        status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_REQUEST,
                                AdapterConstants.ARTICLE_SYSTEM, requestStatus.getStatus() + ": "
                                        + requestStatus.getMessage(), correlId);
                        break;

                    }
                }
            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_STATE,
                    AdapterConstants.ARTICLE_SYSTEM, message, correlId);

        }

        response = status.toJsonObject();
        return response;
    }

    /**
     * Handles a response in a request/response operation.
     *
     * @param op
     *            the full JSON operation object.
     *
     * @param runtimeManager
     *            the runtime manager instance responsible for managing this system instance.
     *
     * @param correlId
     *            The correlation ID of the request.
     *
     * @return A JSON status object.
     */
    public static JSON response(JSON op, RuntimeManager runtimeManager, String correlId) {

        JSON response = new JSON();
        AdapterStatus status = new AdapterStatus(correlId);

        try {

            String producer = op.getString(AdapterConstants.FIELD_REQUEST_RESPONSE);
            String respondTo = op.getString(AdapterConstants.FIELD_SOLICIT_RESPONSE);
            String msg = op.getJSON(AdapterConstants.FIELD_MESSAGE).toString();
            String encoding = op.getString(AdapterConstants.FIELD_ENCODING);

            if (respondTo == null || msg == null || correlId == null) {

                status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_RESPONSE,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FIELD_ERROR, correlId);

            } else {

                /* Send the response */
                ServiceDescriptor respondToDescriptor = new ServiceDescriptor(respondTo);
                ServiceDescriptor producerDescriptor = new ServiceDescriptor(producer);
                RuntimeStatus responseStatus = runtimeManager.response(respondToDescriptor, producerDescriptor, msg,
                        encoding, correlId);

                if (responseStatus.getStatus() != RuntimeStatus.Status.OK) {
                    status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_RESPONSE,
                            AdapterConstants.ARTICLE_SYSTEM, responseStatus.getStatus() + ": "
                                    + responseStatus.getMessage(), correlId);

                }
            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_STATE,
                    AdapterConstants.ARTICLE_SYSTEM, message, correlId);

        }

        response = status.toJsonObject();
        return response;
    }

    /**
     * Handles a notify message.
     *
     * @param op
     *            the full JSON operation object.
     *
     * @param runtimeManager
     *            the runtime manager instance responsible for managing this system instance.
     *
     * @param correlId
     *            The correlation ID of the request.
     *
     * @return A JSON status object.
     */
    public static JSON notify(JSON op, RuntimeManager runtimeManager, String correlId) {

        JSON response = new JSON();
        AdapterStatus status = new AdapterStatus(correlId);

        try {

            JSONArray sendToList = op.getJSONArray(AdapterConstants.FIELD_LISTENER);
            String msg = op.getJSON(AdapterConstants.FIELD_MESSAGE).toString();
            String producer = op.getString(AdapterConstants.FIELD_NOTIFICATION);
            String encoding = op.getString(AdapterConstants.FIELD_ENCODING);

            if (sendToList == null || producer == null || msg == null) {

                status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_NOTIFY,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FIELD_ERROR, correlId);

            } else {

                ServiceDescriptor producerDescriptor = new ServiceDescriptor(producer);

                /* For each target service... */
                for (Object sendTo : sendToList) {

                    ServiceDescriptor sendToDescriptor = new ServiceDescriptor(sendTo.toString());

                    /* Start the request */
                    RuntimeStatus notifyStatus = runtimeManager.notify(sendToDescriptor, producerDescriptor, msg,
                            encoding, correlId);

                    if (notifyStatus.getStatus() != RuntimeStatus.Status.OK) {
                        status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_NOTIFY,
                                AdapterConstants.ARTICLE_SYSTEM, notifyStatus.getStatus() + ": "
                                        + notifyStatus.getMessage(), correlId);
                        break;

                    }
                }
            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_STATE,
                    AdapterConstants.ARTICLE_SYSTEM, message, correlId);

        }

        response = status.toJsonObject();
        return response;
    }

    /**
     * Publishes an output feed message.
     *
     * @param op
     *            the full JSON operation object.
     *
     * @param runtimeManager
     *            the runtime manager instance responsible for managing this system instance.
     *
     * @param correlId
     *            The correlation ID of the request.
     *
     * @return A JSON status object.
     */
    public static JSON publish(JSON op, RuntimeManager runtimeManager, String correlId) {

        JSON response = null;
        AdapterStatus status = new AdapterStatus(correlId);

        try {

            String outputFeed = op.getString(AdapterConstants.FIELD_OUTPUT_FEED);
            String msg = op.getJSON(AdapterConstants.FIELD_MESSAGE).toString();
            String encoding = op.getString(AdapterConstants.FIELD_ENCODING);

            if (outputFeed == null || msg == null) {

                status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_PUBLISH,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FIELD_ERROR, correlId);

            } else {

                ServiceDescriptor outputFeedDescriptor = new ServiceDescriptor(outputFeed);

                /* Push the message onto the Fabric on-ramp */
                RuntimeStatus publishStatus = runtimeManager.publish(outputFeedDescriptor, msg, encoding);

                if (publishStatus.getStatus() != RuntimeStatus.Status.OK) {
                    status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_PUBLISH,
                            AdapterConstants.ARTICLE_SYSTEM, publishStatus.getStatus() + ": "
                                    + publishStatus.getMessage(), correlId);
                }
            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_STATE,
                    AdapterConstants.ARTICLE_SYSTEM, message, correlId);

        }

        response = status.toJsonObject();
        return response;
    }

    /**
     * Subscribes to a list of output feeds.
     *
     * @param op
     *            the full JSON operation object.
     *
     * @param runtimeManager
     *            the runtime manager instance responsible for managing this system instance.
     *
     * @param correlId
     *            The correlation ID of the request.
     *
     * @return A JSON status object.
     */
    public static JSON subscribe(JSON op, RuntimeManager runtimeManager, String correlId) {

        JSON response = null;
        AdapterStatus status = new AdapterStatus(correlId);
        List<ServiceDescriptor> subscribedList = new ArrayList<ServiceDescriptor>();
        String inputFeed = null;

        try {

            JSONArray outputFeedPatterns = op.getJSONArray(AdapterConstants.FIELD_OUTPUT_FEEDS);
            inputFeed = op.getString(AdapterConstants.FIELD_INPUT_FEED);

            if (outputFeedPatterns == null || inputFeed == null || correlId == null) {

                status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_SUBSCRIBE,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FIELD_ERROR, correlId);

            } else {

                ServiceDescriptor inputFeedDescriptor = new ServiceDescriptor(inputFeed);
                List<ServiceDescriptor> patternList = new ArrayList<ServiceDescriptor>();

                /* Extract the list of output feed patterns from the message */

                for (int sf = 0; sf < outputFeedPatterns.size(); sf++) {

                    String nextPattern = outputFeedPatterns.getString(sf);
                    ServiceDescriptor nextDescriptor = new ServiceDescriptor(nextPattern);
                    patternList.add(nextDescriptor);

                }

                ServiceDescriptor[] patternDescriptors = patternList.toArray(new ServiceDescriptor[patternList.size()]);

                /* Subscribe */

                RuntimeStatus subscribeStatus = runtimeManager.subscribe(patternDescriptors, inputFeedDescriptor,
                        subscribedList);

                if (!subscribeStatus.isOK()) {
                    status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_SUBSCRIBE,
                            AdapterConstants.ARTICLE_SYSTEM, subscribeStatus.getStatus() + ": "
                                    + subscribeStatus.getMessage(), correlId);
                }

                response = JSONAdapter.buildSubscriptionResponse(subscribedList, inputFeedDescriptor, correlId);
            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_STATE,
                    AdapterConstants.ARTICLE_SYSTEM, message, correlId);

        }

        response = (response == null) ? status.toJsonObject() : response;

        return response;
    }

    /**
     * Unsubscribe from an output feed.
     *
     * @param op
     *            the full JSON operation object.
     *
     * @param runtimeManager
     *            the runtime manager instance responsible for managing this system instance.
     *
     * @param correlId
     *            The correlation ID of the request.
     *
     * @return A JSON status object.
     */
    public static JSON unsubscribe(JSON op, RuntimeManager runtimeManager, String correlId) {

        JSON response = new JSON();
        AdapterStatus status = new AdapterStatus(correlId);

        try {

            JSONArray sourceFeedList = op.getJSONArray(AdapterConstants.FIELD_OUTPUT_FEEDS);
            String deliverTo = op.getString(AdapterConstants.FIELD_INPUT_FEED);

            if (deliverTo == null) {

                status = new AdapterStatus(AdapterConstants.ERROR_PARSE, AdapterConstants.OP_CODE_UNSUBSCRIBE,
                        AdapterConstants.ARTICLE_SYSTEM, AdapterConstants.STATUS_MSG_FIELD_ERROR, correlId);

            } else {

                ServiceDescriptor[] sourceFeeds = null;

                /* If a list of output-feeds was specified... */
                if (sourceFeedList != null) {

                    sourceFeeds = new ServiceDescriptor[sourceFeedList.size()];

                    /* For each output-feed... */
                    for (int f = 0; f < sourceFeeds.length; f++) {
                        sourceFeeds[f] = new ServiceDescriptor(sourceFeedList.getString(f).toString());
                    }
                }

                ServiceDescriptor deliverToDescriptor = new ServiceDescriptor(deliverTo);

                /* Unsubscribe */
                RuntimeStatus unsubscribeStatus = runtimeManager.unsubscribe(sourceFeeds, deliverToDescriptor);

                if (!unsubscribeStatus.isOK()) {
                    status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_UNSUBSCRIBE,
                            AdapterConstants.ARTICLE_SYSTEM, unsubscribeStatus.getStatus() + ": "
                                    + unsubscribeStatus.getMessage(), correlId);
                }
            }

        } catch (Exception e) {

            String message = e.getClass().getName() + ": " + e.getMessage();
            status = new AdapterStatus(AdapterConstants.ERROR_ACTION, AdapterConstants.OP_CODE_STATE,
                    AdapterConstants.ARTICLE_SYSTEM, message, correlId);

        }

        response = status.toJsonObject();
        return response;
    }

    /**
     * Handles the disconnection of a client.
     *
     * @param op
     *            the full JSON operation object.
     *
     * @param runtimeManager
     *            the runtime manager instance responsible for managing this system instance.
     *
     * @return <code>null</code>.
     */
    public static JSON disconnect(JSON op, RuntimeManager runtimeManager) {

        String clientID = null;

        try {

            clientID = op.getString(AdapterConstants.FIELD_CLIENT);

            if (clientID == null) {

                Logger logger = Logger.getLogger("fabric.services.jsonclient.articles");
                logger.log(Level.INFO, "Disconnection message missing field [{0}]:\n{1}", new Object[] {
                        AdapterConstants.FIELD_CLIENT, op.toString()});

            } else {

                /* Clean-up anything left behind by the client */
                runtimeManager.cleanup(clientID);

            }

        } catch (Exception e) {

            Logger logger = Logger.getLogger("fabric.services.jsonclient.articles");
            logger.log(Level.WARNING, "Exception cleaning up after client [{0}]: {1}", new Object[] {clientID,
                    e.getMessage()});
            logger.log(Level.FINEST, "Full exception: ", e);

        }

        return null;
    }
}
