package net.powermatcher.test.osgi;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import net.powermatcher.api.messages.BidUpdate;
import net.powermatcher.api.monitoring.events.IncomingPriceUpdateEvent;
import net.powermatcher.api.monitoring.events.OutgoingBidEvent;
import net.powermatcher.core.BaseMatcherEndpoint;
import net.powermatcher.core.auctioneer.Auctioneer;
import net.powermatcher.core.bidcache.AggregatedBid;
import net.powermatcher.core.bidcache.BidCache;
import net.powermatcher.core.concentrator.Concentrator;
import net.powermatcher.examples.Freezer;
import net.powermatcher.examples.PVPanelAgent;
import net.powermatcher.examples.StoringObserver;
import net.powermatcher.remote.websockets.AgentEndpointProxyWebsocket;
import net.powermatcher.remote.websockets.MatcherEndpointProxyWebsocket;

import org.osgi.service.cm.Configuration;
public class RemoteClusterTests extends OsgiTestCase {
	private final String FACTORY_PID_AGENT_PROXY = "net.powermatcher.remote.websockets.AgentEndpointProxyWebsocket";
	private final String FACTORY_PID_MATCHER_PROXY = "net.powermatcher.remote.websockets.MatcherEndpointProxyWebsocket";
	private final String AGENT_ID_AGENT_PROXY = "aep1";
	private final String AGENT_ID_MATCHER_PROXY = "mep1";
	
	private StoringObserver observer;
	
	private Configuration pvPanelConfig;
	private Configuration freezerConfig;

	private Concentrator concentrator;
	
    /**
     * Tests a simple buildup of a cluster in OSGI and sanity tests.
     * Custer consists of Auctioneer, Concentrator and 2 remote agents.
     */
    public void testSimpleClusterBuildUpWithRemoteAgent() throws Exception {
    	// Setup default remote enabled cluster
    	setupRemoteCluster();
    	
    	//Checking to see if all agents send bids
    	Thread.sleep(10000);
    	checkBidsFullCluster(observer);
    }

    /**
     * Tests whether agent removal actually makes the bid obsolete of this agent
     * The agent should also not receive any price updates.
     * After attaching the remote agent, bids must be complete again.
     */
    public void testAgentRemoval() throws Exception {
    	// Setup default remote enabled cluster
    	setupRemoteCluster();
    	
    	// Checking to see if all agents send bids
    	Thread.sleep(10000);
    	checkBidsFullCluster(observer);
    	
    	// disconnect Freezer
    	clusterHelper.disconnectAgent(configAdmin, freezerConfig.getPid());
    	
    	// Checking to see if the Freezer is no longer participating
    	observer.clearEvents();
    	Thread.sleep(10000);
    	checkBidsClusterNoFreezer(observer, concentrator);
    	
    	// Re-add Freezer agent, it should not receive bids from previous freezer
    	observer.clearEvents();
    	freezerConfig = clusterHelper.createConfiguration(configAdmin, clusterHelper.getFactoryPidFreezer(), 
    			clusterHelper.getFreezerProperties(clusterHelper.getAgentIdFreezer() , clusterHelper.getAgentIdConcentrator() , 4));
    	Thread.sleep(10000);
    	checkBidsFullCluster(observer);
    }

    private void setupRemoteCluster() throws Exception {
    	// Create simple cluster
    	Configuration auctioneerConfig = clusterHelper.createConfiguration(configAdmin, clusterHelper.getFactoryPidAuctioneer(), 
    			clusterHelper.getAuctioneerProperties(clusterHelper.getAgentIdAuctioneer(), 5000));
    	clusterHelper.checkServiceByPid(context, auctioneerConfig.getPid(), Auctioneer.class);
    	
    	Configuration concentratorConfig = clusterHelper.createConfiguration(configAdmin, clusterHelper.getFactoryPidConcentrator(), 
    			clusterHelper.getConcentratorProperties(clusterHelper.getAgentIdConcentrator(), clusterHelper.getAgentIdAuctioneer(), 5000));
    	concentrator = clusterHelper.getServiceByPid(context, concentratorConfig.getPid(), Concentrator.class);
 
    	// Create agent proxy
    	Configuration agentProxyConfiguration = clusterHelper.createConfiguration(configAdmin, FACTORY_PID_AGENT_PROXY, 
    			getAgentProxyProperties(AGENT_ID_AGENT_PROXY, clusterHelper.AGENT_ID_CONCENTRATOR, AGENT_ID_MATCHER_PROXY));
    	clusterHelper.checkServiceByPid(context, agentProxyConfiguration.getPid(), AgentEndpointProxyWebsocket.class);
    	
    	// Create matcher proxy
    	Configuration matcherProxyConfiguration = clusterHelper.createConfiguration(configAdmin, FACTORY_PID_MATCHER_PROXY, 
    			getMatcherProxyProperties(AGENT_ID_MATCHER_PROXY, AGENT_ID_AGENT_PROXY));
    	clusterHelper.checkServiceByPid(context, matcherProxyConfiguration.getPid(), MatcherEndpointProxyWebsocket.class);

    	// Wait for cluster information to be exchanged
    	// TODO this is not correct, since it must be able to handle missing cluster information on the remote end
    	Thread.sleep(2000);
    	
    	// Create local PvPanel
    	pvPanelConfig = clusterHelper.createConfiguration(configAdmin, 
    			clusterHelper.getFactoryPidPvPanel(), clusterHelper.getPvPanelProperties(clusterHelper.getAgentIdPvPanel(), clusterHelper.getAgentIdConcentrator(), 4));
    	clusterHelper.checkServiceByPid(context, pvPanelConfig.getPid(), PVPanelAgent.class);

    	// Create remove Freezer -> connected to matcher endpoint proxy
    	freezerConfig = clusterHelper.createConfiguration(configAdmin, 
    			clusterHelper.getFactoryPidFreezer(), clusterHelper.getFreezerProperties(clusterHelper.getAgentIdFreezer(), AGENT_ID_MATCHER_PROXY, 4));
    	clusterHelper.checkServiceByPid(context, freezerConfig.getPid(), Freezer.class);
    	
    	// Wait a little time for all components to become satisfied / active
    	Thread.sleep(2000);
    	
    	// check Auctioneer alive
    	assertEquals(true, clusterHelper.checkActive(scrService, clusterHelper.getFactoryPidAuctioneer()));
    	// check Concentrator alive
    	assertEquals(true, clusterHelper.checkActive(scrService, clusterHelper.getFactoryPidConcentrator()));
    	// check PvPanel alive
    	assertEquals(true, clusterHelper.checkActive(scrService, clusterHelper.getFactoryPidPvPanel()));
    	// check Freezer alive
    	assertEquals(true, clusterHelper.checkActive(scrService, clusterHelper.getFactoryPidFreezer()));
    	
    	//Create StoringObserver
    	Configuration storingObserverConfig = clusterHelper.createConfiguration(configAdmin, clusterHelper.getFactoryPidObserver(), clusterHelper.getStoringObserverProperties());
    	
    	// Wait for StoringObserver to become active
    	observer = clusterHelper.getServiceByPid(context, storingObserverConfig.getPid(), StoringObserver.class);
    }
    
    private void checkBidsFullCluster(StoringObserver observer) {
    	// Are any bids available for each agent (at all)
    	assertFalse(observer.getOutgoingBidEvents(clusterHelper.getAgentIdConcentrator()).isEmpty());
    	assertFalse(observer.getOutgoingBidEvents(clusterHelper.getAgentIdPvPanel()).isEmpty());
    	assertFalse(observer.getOutgoingBidEvents(clusterHelper.getAgentIdFreezer()).isEmpty());
    	
    	// Validate bidnumbers
    	checkBidNumbers(observer, clusterHelper.getAgentIdConcentrator());
    	checkBidNumbers(observer, clusterHelper.getAgentIdFreezer());
    	checkBidNumbers(observer, clusterHelper.getAgentIdPvPanel());
    }

    private void checkBidNumbers(StoringObserver observer, String agentId) {
    	// Validate bidnumber incoming from concentrator for correct agent
    	List<OutgoingBidEvent> agentBids = observer.getOutgoingBidEvents(agentId);
    	List<IncomingPriceUpdateEvent> receivedPrices = observer.getIncomingPriceUpdateEvents(agentId);

    	for (IncomingPriceUpdateEvent priceEvent : receivedPrices) {
    		int priceBidnumber = priceEvent.getPriceUpdate().getBidNumber();
    		boolean validBidNumber = false;
    		
    		for (OutgoingBidEvent bidEvent : agentBids) {
    			if (bidEvent.getBidUpdate().getBidNumber() == priceBidnumber) {
    				validBidNumber = true;
    			}
    		}

    		assertTrue("Price bidnumber " + priceBidnumber + " is unknown in bids for agent " + agentId, validBidNumber);
    	}
    }
    
    private void checkBidsClusterNoFreezer(StoringObserver observer, Concentrator concentrator) {
    	assertFalse(observer.getOutgoingBidEvents(clusterHelper.getAgentIdConcentrator()).isEmpty());
    	assertFalse(observer.getOutgoingBidEvents(clusterHelper.getAgentIdPvPanel()).isEmpty());
    	assertTrue(observer.getOutgoingBidEvents(clusterHelper.getAgentIdFreezer()).isEmpty());

    	// Check aggregated bid does no longer contain freezer, by checking last aggregated against panel bids
    	List<OutgoingBidEvent> concentratorBids = observer.getOutgoingBidEvents(clusterHelper.getAgentIdConcentrator());
    	List<OutgoingBidEvent> panelBids = observer.getOutgoingBidEvents(clusterHelper.getAgentIdPvPanel());
    	
    	OutgoingBidEvent concentratorBid = concentratorBids.get(concentratorBids.size()-1);
    	boolean foundBid = false;
    	for (OutgoingBidEvent panelBid : panelBids) {
    		if (panelBid.getBidUpdate().getBid().toArrayBid().equals(concentratorBid.getBidUpdate().getBid().toArrayBid())) {
    			foundBid = true;
    		}
    	}
    	
    	assertTrue("Concentrator still contains freezer bid", foundBid);

    	// Validate last aggregated bid contains only pvPanel
    	BaseMatcherEndpoint matcherPart = clusterHelper.getPrivateField(concentrator, "matcherPart", BaseMatcherEndpoint.class);
    	BidCache bidCache = clusterHelper.getPrivateField(matcherPart, "bidCache", BidCache.class);
    	AggregatedBid lastBid = clusterHelper.getPrivateField(bidCache, "lastBid", AggregatedBid.class);
    	assertTrue(lastBid.getAgentBidReferences().containsKey(clusterHelper.getAgentIdPvPanel()));
    	assertFalse(lastBid.getAgentBidReferences().containsKey(AGENT_ID_AGENT_PROXY));

    	// Validate bidcache contains both agents, but Freezer bid is 0
    	@SuppressWarnings("unchecked")
		Map<String, BidUpdate> agentBids = (Map<String, BidUpdate>)clusterHelper.getPrivateField(bidCache, "agentBids", Object.class);
    	assertTrue(agentBids.containsKey(clusterHelper.getAgentIdPvPanel()));
    	assertFalse(agentBids.containsKey(AGENT_ID_AGENT_PROXY));
    }
    
    private Dictionary<String, Object> getAgentProxyProperties(String agentId, String agentIdMatcher, String remoteAgentEndpointId) throws Exception {
    	Dictionary<String, Object> properties = new Hashtable<String, Object>();
    	properties.put("agentId", agentId);
    	properties.put("desiredParentId", agentIdMatcher);
    	properties.put("remoteAgentEndpointId", remoteAgentEndpointId);

    	return properties;
    }
	
    private Dictionary<String, Object> getMatcherProxyProperties(String agentId, String desiredConnectionId) throws Exception {
    	Dictionary<String, Object> properties = new Hashtable<String, Object>();
    	properties.put("agentId", agentId);
    	properties.put("desiredConnectionId", desiredConnectionId);
    	properties.put("powermatcherUrl", "ws://localhost:8080/powermatcher/websockets/agentendpoint");
    	properties.put("reconnectTimeout", 30);
    	properties.put("connectTimeout", 60);

    	return properties;
    }
}
