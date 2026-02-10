package infrastructure.resources.rest.dto;

import java.util.List;

public class ConsulService {
    public Service Service;
    public List<Check> Checks;    
    
    public static class Service {
        public String ID;
        public String Service;
        public String Address;
        public int Port;
    }
    
    public static class Check {
        public String Node;
        public String CheckID;
        public String Name;
        public String Status;
    }
}
