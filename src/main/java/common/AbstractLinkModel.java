package common;

import error.OTMErrorLog;
import error.OTMException;
import packet.AbstractPacketLaneGroup;
import packet.PacketLink;
import packet.PacketSplitter;
import runner.Scenario;
import utils.OTMUtils;

import java.util.*;
import java.util.stream.IntStream;

import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.toList;

public abstract class AbstractLinkModel {

    public Class myPacketClass;
    public common.Link link;

    //////////////////////////////////////////////////////////////
    // abstract methods
    //////////////////////////////////////////////////////////////

    abstract public void set_road_param(jaxb.Roadparam r, float sim_dt_sec);
//    abstract public void add_native_vehicle_packet(float timestamp, PacketLink vp) throws OTMException;
    abstract public void validate(OTMErrorLog errorLog);
    abstract public void initialize(Scenario scenario) throws OTMException;
    abstract public void reset();
    abstract public float get_ff_travel_time(); // seconds
    abstract public float get_capacity_vps();   // vps

    //////////////////////////////////////////////////////////////
    // construction
    //////////////////////////////////////////////////////////////

    public AbstractLinkModel(common.Link link){
        this.link = link;
    }

    //////////////////////////////////////////////////////////////
    // public
    //////////////////////////////////////////////////////////////

    public Double get_supply(){
        return link.lanegroups.values().stream().mapToDouble(x->x.get_supply()).sum();
    }

    public void add_vehicle_packet(float timestamp, PacketLink vp) throws OTMException {

        if(vp.isEmpty())
            return;

        // sink or many-to-one
        // this implies that next-link is trivial
        // and (for now) target_lanegroup is trivial
        if(link.packet_splitter==null){

            // if sink, encode by using current link id as nextlink.
            Long outlink_id = link.is_sink ? link.getId() : link.end_node.out_links.values().iterator().next().getId();
            AbstractPacketLaneGroup packet = PacketSplitter.cast_packet_null_splitter(myPacketClass,vp,outlink_id);
            AbstractLaneGroup join_lanegroup = vp.arrive_to_lanegroups.iterator().next();
            join_lanegroup.add_native_vehicle_packet(timestamp,packet);
            return;
        }

        // tag the packet with next_link and target_lanegroups
        Map<Long, AbstractPacketLaneGroup> lanegroup_packets = link.packet_splitter.split_packet(myPacketClass,vp);

        // process each lanegroup packet
        for(Map.Entry<Long, AbstractPacketLaneGroup> e : lanegroup_packets.entrySet()){

            Long outlink_id = e.getKey();
            AbstractPacketLaneGroup lanegroup_packet = e.getValue();

            if(lanegroup_packet.isEmpty())
                continue;

            lanegroup_packet.target_lanegroups = link.outlink2lanegroups.get(outlink_id);

            if(lanegroup_packet.target_lanegroups==null)
                throw new OTMException(String.format("target_lanegroups==null.\nThis may be an error in split ratios. " +
                        "There is no access from link " + link.getId() + " to " +
                        "link " + outlink_id+ ". A possible cause is that there is " +
                        "a positive split ratio between these two links."));

            // candidates lanegroups are those where the packet has arrived
            // intersected with those that can reach the outlink
            // TODO: This can be removed if there is a model for "changing lanes" to another lanegroup
            Set<AbstractLaneGroup> candidate_lanegroups = OTMUtils.intersect( vp.arrive_to_lanegroups , lanegroup_packet.target_lanegroups );

            if(candidate_lanegroups.isEmpty()) {
                // in this case the vehicle has arrived to lanegroups for which there is
                // no connection to the out link.
                // With lane changing implemented, this vehicle would then have to
                // change lanes (lanegroups) over the length of the link.
                // For now, just switch it to one of the connecting lanegroups.

//                throw new OTMException("candidate_lanegroups.isEmpty(): in link " + link.getId() + ", vehicle arrived to lanegroups " +
//                        vpb.arrive_to_lanegroups + " with target lanegroup " + target_lanegroups);
                candidate_lanegroups = link.outlink2lanegroups.get(outlink_id);
            }

            // choose the best one
            AbstractLaneGroup join_lanegroup = choose_best_lanegroup(candidate_lanegroups);

            // TODO: FOR MESO and MICRO MODELS, CHECK THAT THERE IS AT LEAST 1 VEHICLE WORTH OF SUPPLY.

            // if all candidates are full, then choose one that is closest and not full
            if(join_lanegroup==null) {

                join_lanegroup = choose_closest_that_is_not_full(vp.arrive_to_lanegroups,candidate_lanegroups,lanegroup_packet.target_lanegroups);

                // put lane change requests on the target lane groups
                // TODO: REDO THIS
//                add_lane_change_request(timestamp,lanegroup_packet,join_lanegroup,lanegroup_packet.target_lanegroups,Queue.Type.transit);
            }

            // add the packet to it
            join_lanegroup.add_native_vehicle_packet(timestamp,lanegroup_packet);

        }

    }

    public float get_max_vehicles(){
        return (float) link.lanegroups.values().stream().map(x->x.max_vehicles).mapToDouble(i->i).sum();
    }

    //////////////////////////////////////////////////////////////
    // private
    //////////////////////////////////////////////////////////////

    /**
     * select the lane group with the most space per lane.
     */
    public static AbstractLaneGroup choose_best_lanegroup(Collection<AbstractLaneGroup> candidate_lanegroups){

        if(candidate_lanegroups.size()==1)
            return candidate_lanegroups.iterator().next();

        Optional<AbstractLaneGroup> best_lanegroup = candidate_lanegroups.stream()
                .max(Comparator.comparing(AbstractLaneGroup::get_space_per_lane));

        return best_lanegroup.isPresent() ? best_lanegroup.get() : null;
    }

    private AbstractLaneGroup choose_closest_that_is_not_full(Set<AbstractLaneGroup> arrive_to_lanegroups,Set<AbstractLaneGroup> candidate_lanegroups,Set<AbstractLaneGroup> target_lanegroups) throws OTMException {

        // these will be selected from among the lanegroups that do not directly connect to
        // the output link.
        List<AbstractLaneGroup> second_best_candidates = new ArrayList(OTMUtils.setminus(arrive_to_lanegroups,candidate_lanegroups));

        // this should not be empty. Otherwise the assumption that the link was checked for space is vuilated.
        if(second_best_candidates.isEmpty())
            throw new OTMException("This should not happen.");

        // from these select the one that is closest to the destination lanegroups (ie minimizes lane changes)

        // find the range of lanes of the target lanegroups
        List<Integer> target_lanes = target_lanegroups.stream()
                .map(x->x.lanes)
                .flatMap(x->x.stream())
                .collect(toList());
        Integer min_lane = target_lanes.stream().mapToInt(x->x).min().getAsInt();
        Integer max_lane = target_lanes.stream().mapToInt(x->x).max().getAsInt();

        // compute the distance of each second best candidate to the targets
        List<Integer> distance_to_target = second_best_candidates.stream()
                .map(x->x.distance_to_lanes(min_lane,max_lane))
                .collect(toList());

        // find the index of the smallest distance
        int index = IntStream.range(0,distance_to_target.size()).boxed()
                .min(comparingInt(distance_to_target::get))
                .get();

        // pick that lanegroup to join
        return second_best_candidates.get(index);
    }

//    private void add_lane_change_request(float timestamp, AbstractPacketLaneGroup packet, AbstractLaneGroup from_lanegroup, Set<AbstractLaneGroup> to_lanegroups, Queue.Type queue_type) throws OTMException{
//
//        // the packet should contain a single models.ctm.pq vehicle
//        if(packet.vehicles.isEmpty() || packet.vehicles.size()!=1)
//            throw new OTMException("This is weird.");
//
//        AbstractVehicle abs_vehicle = packet.vehicles.iterator().next();
//        if( !(abs_vehicle instanceof Vehicle) )
//            throw new OTMException("This is weird.");
//
//        Vehicle vehicle = (Vehicle) abs_vehicle;
//
//        // define from queue
//        Queue from_queue = null;
//        switch(queue_type){
//            case transit:
//                from_queue = ((LaneGroup)from_lanegroup).transit_queue;
//                break;
//            case waiting:
//                from_queue = ((LaneGroup)from_lanegroup).waiting_queue;
//                break;
//        }
//
//        // create the request and add it to the destination lanegroup
//        for(AbstractLaneGroup lg : to_lanegroups) {
//            Queue to_queue = null;
//            switch(queue_type){
//                case transit:
//                    to_queue = ((LaneGroup) lg).transit_queue;
//                    break;
//                case waiting:
//                    to_queue = ((LaneGroup) lg).waiting_queue;
//                    break;
//            }
//            to_queue.submit_lane_change_request( new LaneChangeRequest(timestamp, vehicle, from_queue,to_queue));
//        }
//
//        // vehicle is requesting lane change
//        vehicle.waiting_for_lane_change = true;
//
//    }


}
