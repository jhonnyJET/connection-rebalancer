package infrastructure.scheduler;

import api.WsSessionApi;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class RebalancerTask {

    @Inject
    private WsSessionApi wsSessionApi;

    @Scheduled(every = "30s")
    public void analyzeConnectionRebalance(){
        // Logger.getAnonymousLogger().log(Level.INFO, "Starting session rebalance");
        wsSessionApi.analyzeSessionServerBalance();
    }

    @Scheduled(every = "10s")
    public void analyzeServerUtilization(){
        Logger.getAnonymousLogger().log(Level.INFO, "Starting session utilization analysis");
        wsSessionApi.analyzeSessionServerUtilization();
    }    
}
