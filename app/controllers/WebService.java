package controllers;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.ws.Service;

import models.BoyerMoore;
import models.EventTopic;
import models.ModelManager;
import models.PutGetClient;
import models.SupportedTopicsXML;
import models.translator.*;
import models.eventformat.*;

import org.jdom.input.SAXBuilder;
import org.event_processing.events.types.FacebookCepResult;
import org.event_processing.events.types.FacebookStatusFeedEvent;
import org.event_processing.events.types.TwitterEvent;
import org.junit.Test;
import org.ontoware.rdf2go.exception.ModelRuntimeException;
import org.ontoware.rdf2go.model.Syntax;
import org.ontoware.rdf2go.model.node.impl.URIImpl;
import org.ontoware.rdfreactor.runtime.CardinalityException;
import org.ontoware.rdfreactor.schema.rdfs.List;
import org.petalslink.dsb.notification.client.http.HTTPNotificationProducerRPClient;
import org.petalslink.dsb.notification.client.http.simple.HTTPProducerClient;
import org.petalslink.dsb.notification.client.http.simple.HTTPSubscriptionManagerClient;
import org.petalslink.dsb.notification.commons.NotificationException;
import org.w3c.dom.Document;

import play.Logger;
import play.Play;
import play.libs.F.Promise;
import play.libs.WS;
import play.libs.WS.HttpResponse;
import play.libs.WS.WSRequest;
import play.mvc.Controller;
import play.mvc.Util;
import play.templates.TemplateLoader;

import com.ebmwebsourcing.easycommons.xml.XMLHelper;
import com.ebmwebsourcing.wsstar.basefaults.datatypes.impl.impl.WsrfbfModelFactoryImpl;
import com.ebmwebsourcing.wsstar.basenotification.datatypes.impl.impl.WsnbModelFactoryImpl;
import com.ebmwebsourcing.wsstar.resource.datatypes.impl.impl.WsrfrModelFactoryImpl;
import com.ebmwebsourcing.wsstar.resourcelifetime.datatypes.impl.impl.WsrfrlModelFactoryImpl;
import com.ebmwebsourcing.wsstar.resourceproperties.datatypes.impl.impl.WsrfrpModelFactoryImpl;
import com.ebmwebsourcing.wsstar.topics.datatypes.api.WstopConstants;
import com.ebmwebsourcing.wsstar.topics.datatypes.impl.impl.WstopModelFactoryImpl;
import com.ebmwebsourcing.wsstar.wsnb.services.INotificationProducerRP;
import com.ebmwebsourcing.wsstar.wsnb.services.impl.util.Wsnb4ServUtils;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.query.QuerySolution;

import eu.play_project.play_commons.constants.Constants;
import eu.play_project.play_platformservices.api.QueryDispatchApi;
import fr.inria.eventcloud.api.CompoundEvent;
import fr.inria.eventcloud.api.Quadruple;
import fr.inria.eventcloud.api.QuadruplePattern;
import fr.inria.eventcloud.api.generators.QuadrupleGenerator;
import fr.inria.eventcloud.api.responses.SparqlSelectResponse;
import fr.inria.eventcloud.api.wrappers.ResultSetWrapper;

/**
 * The WebService controller is in charge of SOAP connection with the DSB.
 * 
 * @author Alexandre Bourdin
 */
public class WebService extends Controller {

	public static String DSB_RESOURCE_SERVICE = Constants.getProperties().getProperty("dsb.notify.endpoint");
	public static String EC_PUTGET_SERVICE = Constants.getProperties().getProperty(
			"eventcloud.putget.endpoint");

	static {
		Wsnb4ServUtils.initModelFactories(new WsrfbfModelFactoryImpl(), new WsrfrModelFactoryImpl(),
				new WsrfrlModelFactoryImpl(), new WsrfrpModelFactoryImpl(), new WstopModelFactoryImpl(),
				new WsnbModelFactoryImpl());
	}

	/**
	 * SOAP endpoint to receive WS-Notifications from the DSB.
	 * 
	 * @param topicId
	 *            : necessary to have a unique endpoint for each topic.
	 */
	public static void soapNotifEndPoint(String topicId) {

		URI eventId = generateRandomUri();
		CompoundEvent event = TranslationUtils.translateWsNotifNotificationToEvent(request.body,
				inputStreamFrom("public/xml/xsd-01.xml"), eventId);

		Collection<Triple> triples = event.getTriples();
		String title = "-";
		String content = "";
		for (Triple t : triples) {
			String predicate = t.getPredicate().toString();
			String object = t.getObject().getLiteralLexicalForm();
			if (BoyerMoore.match("Topic", predicate).size() > 0) {
				title = object;
			} else {
				content += splitUri(t.getSubject().toString())[1] + " : " + splitUri(predicate)[1] + " : "
						+ object + "<br/>";
			}
		}

		ModelManager.get().getTopicById(topicId).multicast(new models.Event(title, content));
	}

	/**
	 * Sends a request to the DSB to get the list of supported topics
	 */
	@Util
	public static ArrayList<EventTopic> getSupportedTopics() {
		INotificationProducerRP resourceClient = new HTTPNotificationProducerRPClient(DSB_RESOURCE_SERVICE);

		try {
			QName qname = WstopConstants.TOPIC_SET_QNAME;
			com.ebmwebsourcing.wsstar.resourceproperties.datatypes.api.abstraction.GetResourcePropertyResponse response = resourceClient
					.getResourceProperty(qname);
			
			Document dom = Wsnb4ServUtils.getWsrfrpWriter().writeGetResourcePropertyResponseAsDOM(response);
			String topicsString = XMLHelper.createStringFromDOMDocument(dom);

			Logger.info("TOPICS STRING: " + topicsString);

			SAXBuilder sxb = new SAXBuilder();
			org.jdom.Document xml = new org.jdom.Document();
			org.jdom.Element root = null;
			ArrayList<EventTopic> topics = new ArrayList<EventTopic>();
			xml = sxb.build(new StringReader(topicsString));
			root = xml.getRootElement();

			SupportedTopicsXML.parseXMLTree(topics, root, "");

			return topics;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	/**
	 * Subscription action, forwards the subscription to the DSB
	 * 
	 * @param et
	 */
	@Util
	public static int subscribe(EventTopic et) {
		Logger.info("Subscribing to topic '%s%s' at broker '%s'", et.uri, et.name, DSB_RESOURCE_SERVICE);

		HTTPProducerClient client = new HTTPProducerClient(DSB_RESOURCE_SERVICE);
		QName topic = new QName(et.uri, et.name, et.namespace);

		String notificationsEndPoint = "http://demo.play-project.eu/webservice/soapnotifendpoint/"
				+ et.getId();
		try {
			et.subscriptionID = client.subscribe(topic, notificationsEndPoint);
			et.alreadySubscribedDSB = true;
		} catch (NotificationException e) {
			e.printStackTrace();
		}

		return 1;
	}

	/**
	 * Unsubscription action, forwards the unsubscription to the DSB
	 * 
	 * @param et
	 */
	@Util
	public static int unsubscribe(EventTopic et) {
		HTTPSubscriptionManagerClient subscriptionManagerClient = new HTTPSubscriptionManagerClient(
				DSB_RESOURCE_SERVICE);
		try {
			subscriptionManagerClient.unsubscribe(et.subscriptionID);
			et.alreadySubscribedDSB = false;
		} catch (NotificationException e) {
			e.printStackTrace();
			return 0;
		}

		return 1;
	}

	/**
	 * Retreives historical events for a given topic
	 * 
	 * @param et
	 * @return
	 */
	@Util
	public static ArrayList<models.Event> getHistorical(EventTopic et) {
		ArrayList<models.Event> events = new ArrayList<models.Event>();

		PutGetClient pgc = new PutGetClient(EC_PUTGET_SERVICE);

		SparqlSelectResponse response = pgc
				.executeSparqlSelect("SELECT ?g ?s ?p ?o WHERE { GRAPH ?g { <http://eventcloud.inria.fr/replace/me/with/a/correct/namespace/"
						+ et.namespace + ":" + et.name + "> ?p ?o } } LIMIT 30");
		String title = "-";
		String content = "";
		ResultSetWrapper result = response.getResult();
		while (result.hasNext()) {
			QuerySolution qs = result.next();
			String predicate = qs.get("p").toString();
			String object = qs.get("o").toString();
			if (BoyerMoore.match("Topic", predicate).size() > 0) {
				title = object;
			} else {
				content = et.namespace + ":" + et.name + " : " + predicate + " : " + object + "<br/>";
			}
			events.add(new models.Event(title, content));
		}
		ArrayList<models.Event> temp = new ArrayList<models.Event>();
		for (int i = 0; i < events.size(); i++) {
			temp.add(events.get(events.size() - i - 1));
		}
		return temp;
	}

	@Util
	public static boolean sendTokenPatternQuery(String token) {
		URL wsdl = null;
		try {
			wsdl = new URL("http://demo.play-project.eu:8085/play/QueryDispatchApi?wsdl");
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}

		QName serviceName = new QName("http://play_platformservices.play_project.eu/", "QueryDispatchApi");

		Service service = Service.create(wsdl, serviceName);
		QueryDispatchApi queryDispatchApi = service.getPort(QueryDispatchApi.class);

		String topic = "\"" + token + "\"";

		String prefix = "PREFIX : <http://streams.play-project.eu/types/>";
		String queryString = prefix + "SELECT ?friend1 ?friend2 ?friend3 ?topic1" + " WHERE " + "WINDOW{ "
				+ "EVENT ?id1{" + "?dtc1 ?typ1 :FacebookStatusFeedEvent." + "?dtc1 :status ?topic1."
				+ "?dtc1 :name ?friend1} " + "FILTER fn:contains(?topic1, " + topic + ")" + "SEQ "
				+ "EVENT ?id2{" + "?dtc2 ?typ1 :FacebookStatusFeedEvent. " + "?dtc2 :status ?topic2."
				+ "?dtc2 :name ?friend2} " + "FILTER fn:contains(?topic2, " + topic + ")" + "SEQ "
				+ "EVENT ?id3{" + "?dtc3 ?typ1 :FacebookStatusFeedEvent. " + "?dtc3 :status ?topic3."
				+ "?dtc3 :name ?friend3} " + "FILTER fn:contains(?topic3, " + topic + ")"
				+ "} (\"P30M\"^^xsd:duration, sliding)";

		try {
			String s = queryDispatchApi.registerQuery(queryString);
			Logger.info(s);
		} catch (Exception e) {
			Logger.error(e.toString());
			return false;
		}
		return true;
	}

	public static Boolean sendFullPatternQuery(String queryString) {
		URL wsdl = null;
		try {
			wsdl = new URL("http://demo.play-project.eu:8085/play/QueryDispatchApi?wsdl");
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}

		QName serviceName = new QName("http://play_platformservices.play_project.eu/", "QueryDispatchApi");

		Service service = Service.create(wsdl, serviceName);
		QueryDispatchApi queryDispatchApi = service
				.getPort(eu.play_project.play_platformservices.api.QueryDispatchApi.class);

		try {
			String s = queryDispatchApi.registerQuery(queryString);
			Logger.info(s);
		} catch (Exception e) {
			Logger.error(e.toString());
			return false;
		}
		return true;
	}

	/**
	 * Notify action triggered by buttons on the web interface Generates a
	 * Facebook status event event and sends it to the DSB
	 */
	public static void testFacebookStatusFeedEvent() throws ModelRuntimeException, IOException {
		String eventId = Stream.FacebookStatusFeed.getUri() + "/" + Math.random();

		FacebookStatusFeedEvent event = new FacebookStatusFeedEvent(EventHelpers.createEmptyModel(eventId),
				eventId + "#event", true);

		event.setName("Roland Stühmer");
		event.setId("100000058455726");
		event.setLink(new URIImpl("http://graph.facebook.com/roland.stuehmer#"));
		event.setStatus("I bought some JEANS this morning");
		event.setUserLocation("Karlsruhe, Germany");
		event.setEndTime(Calendar.getInstance());
		event.setStream(new URIImpl(Stream.FacebookStatusFeed.getUri()));
		event.getModel().writeTo(System.out, Syntax.Turtle);
		System.out.println();
	}

	/**
	 * Puts the content of a file on an InputStream
	 * 
	 * @param file
	 * @return
	 */
	@Util
	private static InputStream inputStreamFrom(String file) {
		InputStream is = null;

		if (file != null) {
			try {
				is = new FileInputStream(Play.getFile(file));
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
		}

		return is;
	}

	@Util
	private static URI generateRandomUri() {
		String legalChars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

		StringBuilder result = new StringBuilder("http://www.inria.fr/");
		SecureRandom random = new SecureRandom();

		for (int i = 0; i < 20; i++) {
			result.append(random.nextInt(legalChars.length()));
		}

		try {
			return new URI(result.toString());
		} catch (URISyntaxException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Util
	private static String[] splitUri(String uri) {
		if (uri.endsWith("/")) {
			uri = uri.substring(0, uri.length() - 1);
		}

		int slashIndex = uri.lastIndexOf('/');

		if (slashIndex == -1) {
			return new String[] { "", uri };
		} else {
			return new String[] { uri.substring(0, slashIndex), uri.substring(slashIndex + 1) };
		}
	}

}
