import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "kafka-alter-rf",
    description = "A simply utility to alter the replication factor of a topic", version = "0.0.1",
    mixinStandardHelpOptions = true)
public class AlterReplicationFactor implements Runnable {

  @Option(required = false, names = {"-b", "--bootstrap-server"},
      description = "List of Kafka Bootstrap servers", defaultValue = "localhost:9092",
      showDefaultValue = Visibility.ALWAYS)
  private String bootstrapServers = "localhost:9092";

  @Option(required = false, names = {"-c", "--command-config"},
      description = "Config file containing properties like security credentials, etc",
      defaultValue = "", showDefaultValue = Visibility.ALWAYS)
  private String commandConfigFile = "";


  @Option(required = true, names = {"-t", "--topic"},
      description = "Topic to alter replication factor on")
  private String topic = null;

  @Option(required = true, names = {"-r", "--replication-factor"},
      description = "New replication factor")
  private Integer replicationFactor = 1;

  @Spec
  CommandSpec spec;



  /**
   * Takes a number of lists and interleaves them by choosing one from each list
   * 
   * @param <T>
   * @param lists
   * @return
   */
  static <T> List<T> interleave(List<List<T>> lists) {
    int maxSize = lists.stream().mapToInt(List::size).max().orElse(0);
    return IntStream.range(0, maxSize).boxed()
        .flatMap(i -> lists.stream().filter(l -> i < l.size()).map(l -> l.get(i)))
        .collect(Collectors.toList());
  }

  /**
   * Takes a list and returns "take" items back but starting from position
   * 
   * @param <T>
   * @param input
   * @param position
   * @return
   */
  static <T> List<T> rotation(List<T> input, int position, int take) {
    List<T> output = new ArrayList<>();
    for (int i = position; i < take + position; i++) {
      output.add(input.get(i % input.size()));
    }
    System.out.println(output);
    return output;
  }

  /**
   * Putting in a strategy interface so that we could have more clever reassignments (e.g. least
   * amount of steps)
   * 
   * @author sarwar
   *
   */
  public interface ReassignmentStrategy {
    Map<TopicPartition, Optional<NewPartitionReassignment>> reassignments();
  }

  /**
   * This can be moved to another class as just one strategy to builder out the reassignments list
   * 
   * @author sarwar
   *
   */
  public static class RoundRobinAcrossRacksStrategy implements ReassignmentStrategy {
    private final String topic;
    private final List<TopicPartitionInfo> currentPartitions;
    // private final Queue<List<Integer>> permutations;
    private List<Integer> rackAlternatingNodes;
    private int replicationFactor;

    public RoundRobinAcrossRacksStrategy(String topic, Collection<Node> brokers,
        List<TopicPartitionInfo> currentPartitions, int replicationFactor) {
      this.currentPartitions = currentPartitions;
      this.topic = topic;
      this.replicationFactor = replicationFactor;
      List<List<Node>> splitByRackNodes =
          brokers.stream().collect(Collectors.groupingBy(n -> n.rack())).values().stream()
              .collect(Collectors.toList());

      this.rackAlternatingNodes =
          interleave(splitByRackNodes).stream().map(n -> n.id()).collect(Collectors.toList());
    }

    @Override
    public Map<TopicPartition, Optional<NewPartitionReassignment>> reassignments() {
      return currentPartitions.stream()
          .collect(Collectors.toMap(tp -> new TopicPartition(topic, tp.partition()),
              tp -> Optional.of(new NewPartitionReassignment(
                  rotation(rackAlternatingNodes, tp.partition(), replicationFactor)))));
    }


  }

  @Override
  public void run() {
    Properties properties = new Properties();
    properties.put("bootstrap.servers", this.bootstrapServers);

    if (commandConfigFile.length() > 0) {
      Properties fileProps = new Properties();
      try {
        fileProps.load(new FileInputStream(commandConfigFile));
      } catch (Throwable e) {
        throw new CommandLine.ParameterException(spec.commandLine(),"Could not find or read specified file");
      }
      fileProps.forEach((key, value) -> properties.put(key, value)); // merge two props
    }
    
    

    try (AdminClient client = AdminClient.create(properties)) {
      List<TopicPartitionInfo> currentPartitions = client
          .describeTopics(Collections.singleton(topic)).values().get(topic).get().partitions();

      Collection<Node> brokers = client.describeCluster().nodes().get();
      if (replicationFactor > brokers.size()) {
        throw new CommandLine.ParameterException(spec.commandLine(),
            "Replication factor cannot exceed the number of brokers present");
      }
      
      System.out.println("Current Assignments:");
      for (TopicPartitionInfo tpi : currentPartitions) {
        System.out.println(tpi.replicas().stream().map(r -> r.id()).collect(Collectors.toList()));

      }
      
      

      

      ReassignmentStrategy reassignmentStrategy =
          new RoundRobinAcrossRacksStrategy(topic, brokers, currentPartitions, replicationFactor);

      System.out.println("Reassignments:");
      Map<TopicPartition, Optional<NewPartitionReassignment>> reassignments =
          reassignmentStrategy.reassignments();

      // execute reassignment
      client.alterPartitionReassignments(reassignments).all().get();

    } catch(CommandLine.ParameterException p) {
      throw p;
    } catch (Exception e) {
      throw new CommandLine.ParameterException(spec.commandLine(),
          "A fatal exception has occurred. ");

    }
    System.out
        .println("Replication factor for topic " + topic + " updated to " + replicationFactor);


  }


  public static void main(String... args) {

    System.exit(new CommandLine(new AlterReplicationFactor()).execute(args));


    // Properties properties = new Properties();
    // properties.put("bootstrap.servers", "kafka4:9095");
    // String topic = "test";
    // int replicationFactor = 3;
    //
    // try (AdminClient client = AdminClient.create(properties)) {
    // List<TopicPartitionInfo> currentPartitions = client
    // .describeTopics(Collections.singleton(topic)).values().get(topic).get().partitions();
    //
    // System.out.println("Current Assignments:");
    // for (TopicPartitionInfo tpi : currentPartitions) {
    // System.out.println(tpi.replicas().stream().map(r -> r.id()).collect(Collectors.toList()));
    //
    // }
    //
    // Collection<Node> brokers = client.describeCluster().nodes().get();
    //
    // ReassignmentStrategy reassignmentStrategy = new RoundRobinAcrossRacksStrategy(topic, brokers,
    // currentPartitions, replicationFactor);
    //
    // System.out.println("Reassignments:");
    // Map<TopicPartition, Optional<NewPartitionReassignment>> reassignments =
    // reassignmentStrategy.reassignments();
    //
    // //execute reassignment
    // client.alterPartitionReassignments(reassignments).all().get();

    //
    // } catch (Exception e) {
    // e.printStackTrace();
    // }

  }


}