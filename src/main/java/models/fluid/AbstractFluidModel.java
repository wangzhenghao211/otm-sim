package models.fluid;

import commodity.Commodity;
import commodity.Path;
import common.AbstractSource;
import common.Link;
import common.Node;
import dispatch.Dispatcher;
import dispatch.EventFluidModelUpdate;
import dispatch.EventFluidStateUpdate;
import error.OTMException;
import keys.KeyCommPathOrLink;
import models.AbstractModel;
import models.AbstractLaneGroup;
import models.fluid.delete.Cell;
import models.fluid.delete.LaneGroup;
import models.fluid.nodemodel.NodeModel;
import models.fluid.nodemodel.RoadConnection;
import models.fluid.nodemodel.UpLaneGroup;
import packet.PacketLink;
import profiles.DemandProfile;
import runner.Scenario;
import utils.OTMUtils;
import utils.StochasticProcess;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

public abstract class AbstractFluidModel extends AbstractModel implements InterfaceFluidModel {

    public final float max_cell_length;
    public final float dt;
    private Set<Link> source_links;
    private Set<Link> sink_links;
    private Map<Long, NodeModel> node_models;

    public AbstractFluidModel(String name, boolean is_default, float dt, StochasticProcess process, Float max_cell_length) {
        super(AbstractModel.Type.Fluid,name, is_default,process);
        this.dt = dt;
        this.max_cell_length = max_cell_length==null ? -1 : max_cell_length;
    }

    //////////////////////////////////////////////////////////////
    // abstract in AbstractModel
    //////////////////////////////////////////////////////////////

    @Override
    public void reset(Link link) {
        for(AbstractLaneGroup alg : link.lanegroups_flwdn.values()){
            LaneGroup lg = (LaneGroup) alg;
            lg.cells.forEach(x->x.reset());
        }
    }

    @Override
    public void build() {
        links.forEach(link->create_cells(link,max_cell_length));
        node_models.values().forEach(m->m.build());
    }

    @Override
    public void register_with_dispatcher(Scenario scenario, Dispatcher dispatcher, float start_time){
        dispatcher.register_event(new EventFluidModelUpdate(dispatcher, start_time + dt, this));
        dispatcher.register_event(new EventFluidStateUpdate(dispatcher, start_time + dt, this));
    }

    @Override
    public final AbstractSource create_source(Link origin, DemandProfile demand_profile, Commodity commodity, Path path) {
        return new FluidSource(origin,demand_profile,commodity,path);
    }

    //////////////////////////////////////////////////////////////
    // implementation completions from AbstractModel
    //////////////////////////////////////////////////////////////

    @Override
    public void set_links(Set<Link> links) {
        super.set_links(links);

        source_links = links.stream().filter(link->link.is_source).collect(toSet());
        sink_links = links.stream().filter(link->link.is_sink).collect(toSet());

        // create node models for all nodes in this model, except terminal nodes
        Set<Node> all_nodes = links.stream()
                .map(link->link.start_node)
                .filter(node->!node.is_source)
                .collect(toSet());

        all_nodes.addAll(links.stream()
                .map(link->link.end_node)
                .filter(node->!node.is_sink)
                .collect(toSet()));

        node_models = new HashMap<>();
        for(Node node : all_nodes) {
            NodeModel nm = new NodeModel(node);
            node_models.put(node.getId(),nm);
//            node.set_node_model(nm);
        }
    }

    @Override
    public void initialize(Scenario scenario) throws OTMException {
        super.initialize(scenario);

        for(NodeModel node_model : node_models.values())
            node_model.initialize(scenario);
    }

    //////////////////////////////////////////////////////////////
    // state equation
    //////////////////////////////////////////////////////////////

    // called by EventFluidModelUpdate
    public void update_flow(float timestamp) throws OTMException {

        update_flow_I(timestamp);

        // -- MPI communication (in otm-mpi) -- //

        update_flow_II(timestamp);

    }

    // update supplies and demands, then run node model to obtain inter-link flows
    protected void update_flow_I(float timestamp) throws OTMException {

        // lane changes and compute demand and supply
        for(Link link : links)
            compute_lanechange_demand_supply(link,timestamp);

        // compute node inflow and outflow (all nodes except sources)
        node_models.values().forEach(n->n.update_flow(timestamp));

    }

    // compute source and source flows
    // node model exchange packets
    protected void update_flow_II(float timestamp) throws OTMException {

        // add to source links
        for(Link link : source_links){

            for(AbstractSource asource : link.sources){
                FluidSource source = (FluidSource) asource;
                for(Map.Entry<Long,Map<KeyCommPathOrLink,Double>> e : source.source_flows.entrySet()){
                    LaneGroup lg = (LaneGroup) link.lanegroups_flwdn.get(e.getKey());
                    Cell upcell = lg.cells.get(0);
                    upcell.add_vehicles(e.getValue(),null,null);
                }
            }
        }

        // release from sink links
        for(Link link : sink_links){

            for(AbstractLaneGroup alg : link.lanegroups_flwdn.values()) {
                LaneGroup lg = (LaneGroup) alg;
                Map<KeyCommPathOrLink,Double> flow_dwn = lg.get_dnstream_cell().demand_dwn;

                lg.release_vehicles(flow_dwn);

                for(Map.Entry<KeyCommPathOrLink,Double> e : flow_dwn.entrySet())
                    if(e.getValue()>0)
                        lg.update_flow_accummulators(e.getKey(),e.getValue());
            }

        }

        // node models exchange packets
        for(NodeModel node_model : node_models.values()) {

            // flows on road connections arrive to links on give lanes convert to packets and send
            for(RoadConnection rc : node_model.rcs.values()) {
                Link link = rc.rc.get_end_link();
                link.model.add_vehicle_packet(link,timestamp, new PacketLink(rc.f_rs, rc.rc));
            }

            // set exit flows on non-sink lanegroups
            for(UpLaneGroup ulg : node_model.ulgs.values()) {
                ulg.lg.release_vehicles(ulg.f_gs);

                // send lanegroup exit flow to flow accumulator
                for(Map.Entry<KeyCommPathOrLink,Double> e : ulg.f_gs.entrySet())
                    if(e.getValue()>0)
                        ulg.lg.update_flow_accummulators(e.getKey(),e.getValue());
            }

        }

    }

    // called by EventFluidStateUpdate
    // intra link flows and states
    public void update_fluid_state(float timestamp) throws OTMException {
        for(Link link : links)
            update_link_state(link,timestamp);
    }

    //////////////////////////////////////////////////////////////
    // getters
    //////////////////////////////////////////////////////////////

    public NodeModel get_node_model_for_node(Long node_id){
        return node_models.get(node_id);
    }

    //////////////////////////////////////////////////////////////
    // private
    //////////////////////////////////////////////////////////////

    private void create_cells(Link link,float max_cell_length){
        for(AbstractLaneGroup lg : link.lanegroups_flwdn.values()) {

            float r = lg.length/max_cell_length;
            boolean is_source_or_sink = link.is_source || link.is_sink;

            int cells_per_lanegroup = is_source_or_sink ?
                    1 :
                    OTMUtils.approximately_equals(r%1.0,0.0) ? (int) r :  1+((int) r);
            float cell_length_meters = is_source_or_sink ?
                    lg.length :
                    lg.length/cells_per_lanegroup;

            ((LaneGroup) lg).create_cells(cells_per_lanegroup, cell_length_meters);
        }
    }

}
