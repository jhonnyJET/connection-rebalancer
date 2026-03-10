package infrastructure.scheduler;

import api.ScalingApi;
import api.SseSessionApi;
import api.WsSessionApi;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class RebalancerTask {

    @Inject
    private ScalingApi scalingApi;

    @Scheduled(every = "60s")
    public void analyzeConnectionRebalance(){
        scalingApi.analyzeSessionServerBalance();
    }

    @Scheduled(every = "10s")
    public void analyzeServerUtilization(){
        scalingApi.analyzeSessionServerUtilization();
    }    
}
