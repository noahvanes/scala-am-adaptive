/**
 * Implementation of a CESK machine following the AAM approach (Van Horn, David,
 * and Matthew Might. "Abstracting abstract machines." ACM Sigplan
 * Notices. Vol. 45. No. 9. ACM, 2010).
 *
 * A difference with the paper is that we separate the continuation store
 * (KontStore) from the value store (Store). That simplifies the implementation
 * of both stores, and the only change it induces is that we are not able to
 * support first-class continuation as easily (we don't support them at all, but
 * they could be added).
 *
 * Also, in the paper, a CESK state is made of 4 components: Control,
 * Environment, Store, and Kontinuation. Here, we include the environment in the
 * control component, and we distinguish "eval" states from "continuation"
 * states. An eval state has an attached environment, as an expression needs to
 * be evaluated within this environment, whereas a continuation state only
 * contains the value reached.
 */
class AAMRefCounting[Exp : Expression, Abs : JoinLattice, Addr : Address, Time : Timestamp]
    extends EvalKontMachine[Exp, Abs, Addr, Time] {
  def name = "AAMRefCounting"

  /**
   * The store used for continuations is a KontStore (defined in
   * Kontinuation.scala). It is parameterized by continuation addresses, that
   * are element of the KontAddress typeclass.
   */
  trait KontAddr
  case class NormalKontAddress(exp: Exp, time: Time) extends KontAddr {
    override def toString = s"NormalKontAddress($exp)"
  }
  case object HaltKontAddress extends KontAddr {
    override def toString = "HaltKontAddress"
  }

  object KontAddr {
    implicit object KontAddrKontAddress extends KontAddress[KontAddr]
  }

  implicit val stateWithKey = new WithKey[State] {
    type K = KontAddr
    def key(st: State) = st.a
  }

  /**
   * A machine state is made of a control component, a value store, a
   * continuation store, and an address representing where the current
   * continuation lives.
   */
  case class State(control: Control, store: Store[Addr, Abs], kstore: RefCountingKontStore[KontAddr], a: KontAddr, t: Time) {
    override def toString = control.toString

    /**
     * Checks whether a states subsumes another, i.e., if it is "bigger". This
     * is used to perform subsumption checking when exploring the state space,
     * in order to avoid exploring states for which another state that subsumes
     * them has already been explored.
     */
    def subsumes(that: State): Boolean = control.subsumes(that.control) && store.subsumes(that.store) && a == that.a && kstore.subsumes(that.kstore) && t == that.t

    /**
      * When the "root reference" of the state changes, we need to update the reference counts
      * This procedure checks if the root has changed, if so:
      * - it increments the ref count of the new root
      * - it decrements the ref count of the old root
      * Otherwise, no modifications need to be made
      */
    private def changeRoot(kstore: RefCountingKontStore[KontAddr], root: KontAddr): RefCountingKontStore[KontAddr] =
      if (a != root) { kstore.addRef(root).decRef(a) } else { kstore }

    /**
     * Integrates a set of actions (returned by the semantics, see
     * Semantics.scala), in order to generate a set of states that succeeds this
     * one.
     */
    private def integrate(adr: KontAddr, actions: Set[Action[Exp, Abs, Addr]]): Set[State] =
      actions.flatMap({
        /* When a value is reached, we go to a continuation state */
        case ActionReachedValue(v, store, _) => Set(State(ControlKont(v), store, changeRoot(kstore,adr), adr, Timestamp[Time].tick(t)))
        /* When a continuation needs to be pushed, push it in the continuation store */
        case ActionPush(frame, e, env, store, _) => {
          val next = NormalKontAddress(e, t)
          Set(State(ControlEval(e, env), store, changeRoot(kstore.extend(next, Kont(frame,adr)),next), next, Timestamp[Time].tick(t)))
        }
        /* When a value needs to be evaluated, we go to an eval state */
        case ActionEval(e, env, store, _) => Set(State(ControlEval(e, env), store, changeRoot(kstore,adr), adr, Timestamp[Time].tick(t)))
        /* When a function is stepped in, we also go to an eval state */
        case ActionStepIn(fexp, _, e, env, store, _, _) => Set(State(ControlEval(e, env), store, changeRoot(kstore,adr), adr, Timestamp[Time].tick(t, fexp)))
        /* When an error is reached, we go to an error state */
        case ActionError(err) => Set(State(ControlError(err), store, changeRoot(kstore,adr), adr, Timestamp[Time].tick(t)))
      })

    /**
     * Computes the set of states that follow the current state
     */
    def step(sem: Semantics[Exp, Abs, Addr, Time]): Set[State] = control match {
      /* In a eval state, call the semantic's evaluation method */
      case ControlEval(e, env) => integrate(a, sem.stepEval(e, env, store, t))
      /* In a continuation state, call the semantics' continuation method */
      case ControlKont(v) => kstore.lookup(a).flatMap({
        case Kont(frame, next) => integrate(next, sem.stepKont(v, frame, store, t))
      })
      /* In an error state, the state is not able to make a step */
      case ControlError(_) => Set()
    }

    def stepAnalysis[L](analysis: Analysis[L, Exp, Abs, Addr, Time], current: L): L = control match {
      case ControlEval(e, env) => analysis.stepEval(e, env, store, t, current)
      case ControlKont(v) => {
        val konts = kstore.lookup(a).map({
          case Kont(frame, _) => analysis.stepKont(v, frame, store, t, current)
        })
        if (konts.isEmpty) { current }
        else { konts.reduceLeft((x, y) => analysis.join(x, y)) }
      }
      case ControlError(err) => analysis.error(err, current)
    }

    /**
     * Checks if the current state is a final state. It is the case if it
     * reached the end of the computation, or an error
     */
    def halted: Boolean = control match {
      case ControlEval(_, _) => false
      case ControlKont(v) => a == HaltKontAddress
      case ControlError(_) => true
    }
  }
  object State {
    def inject(exp: Exp, env: Iterable[(String, Addr)], store: Iterable[(Addr, Abs)]) =
      State(ControlEval(exp, Environment.initial[Addr](env)),
        Store.initial[Addr, Abs](store), KontStore.refCountStore[KontAddr].addRef(HaltKontAddress), HaltKontAddress, Timestamp[Time].initial(""))
    import scala.language.implicitConversions

    implicit val graphNode = new GraphNode[State, Unit] {
      override def label(s: State) = s.toString
      override def color(s: State) = s.control match {
        case _: ControlEval => Colors.White
        case _: ControlKont if s.halted => Colors.Green
        case _: ControlKont => Colors.Yellow
        case _: ControlError => Colors.Red
      }

      import org.json4s._
      import org.json4s.JsonDSL._
      import org.json4s.jackson.JsonMethods._
      import JSON._
      override def content(s: State) =
        ("control" -> s.control) ~ ("store" -> s.store) ~ ("kstore" -> s.kstore) ~ ("kont" -> s.a.toString) ~ ("time" -> s.t.toString)
    }
  }

  type G = Option[Graph[State, Unit, Unit]]
  case class AAMOutput(halted: Set[State], numberOfStates: Int, time: Double, graph: G, timedOut: Boolean)
      extends Output {
    def finalValues = halted.flatMap(st => st.control match {
      case ControlKont(v) => Set[Abs](v)
      case _ => Set[Abs]()
    })

    def toFile(path: String)(output: GraphOutput) = graph match {
      case Some(g) => output.toFile(g, ())(path)
      case None => println("Not generating graph because no graph was computed")
    }
  }

  /**
   * Performs the evaluation of an expression @param exp (more generally, a
   * program) under the given semantics @param sem. If @param graph is true, it
   * will compute and generate the graph corresponding to the execution of the
   * program (otherwise it will just visit every reachable state). A @param
   * timeout can also be given.
   */
  def eval(exp: Exp, sem: Semantics[Exp, Abs, Addr, Time], graph: Boolean, timeout: Timeout): Output = {
    import scala.language.higherKinds
    /* The fixpoint computation loop. @param todo is the set of states that need to
     * be visited (the worklist). @param visited is the set of states that have
     * already been visited. @param halted is the set of "final" states, where
     * the program has finished its execution (it is only needed so that it can
     * be included in the output, to return the final values computed by the
     * program). @param graph is the current graph that has been computed (if we
     * need to compute it). If we don't need to compute the graph, @param graph
     * is None (see type definition for G above in this file).  Note that the
     * worklist and visited set are "parameterized" and not tied to concrete
     * implementations; but they are basically similar as Set[State].
     */
    @scala.annotation.tailrec
    def loop[WL[_] : WorkList, VS[_] : VisitedSet](todo: WL[State], visited: VS[State], halted: Set[State], graph: G): AAMOutput = {
      if (timeout.reached) {
        /* If we exceeded the maximal time allowed, we stop the evaluation and return what we computed up to now */
        AAMOutput(halted, VisitedSet[VS].size(visited), timeout.time, graph, true)
      } else {
        /* Pick an element from the worklist */
        WorkList[WL].pick(todo) match {
          /* We have an element, hence pick returned a pair consisting of the state to visit, and the new worklist */
          case Some((s, newTodo)) =>
            if (VisitedSet[VS].contains(visited, s)) {
              /* If we already visited the state, or if it is subsumed by another already
               * visited state (i.e., we already visited a state that contains
               * more information than this one), we ignore it. The subsumption
               * part reduces the number of visited states. */
              loop(newTodo, visited, halted, graph)
            } else if (s.halted) {
              /* If the state is a final state, add it to the list of final states and
               * continue exploring the graph */
              loop(newTodo, VisitedSet[VS].add(visited, s), halted + s, graph)
            } else {
              /* Otherwise, compute the successors of this state, update the graph, and push
               * the new successors on the todo list */
              val succs = s.step(sem) /* s.step returns the set of successor states for s */
              val newGraph = graph.map(_.addEdges(succs.map(s2 => (s, (), s2)))) /* add the new edges to the graph: from s to every successor */
              /* then, add new successors to the worklist, add s to the visited set, and loop with the new graph */
              loop(WorkList[WL].append(newTodo, succs), VisitedSet[VS].add(visited, s), halted, newGraph)
            }
          /* No element returned by pick, this means the worklist is empty and we have visited every reachable state */
          case None => AAMOutput(halted, VisitedSet[VS].size(visited), timeout.time, graph, false)
        }
      }
    }
    loop(
      /* Start with the initial state resulting from injecting the program */
      Vector(State.inject(exp, sem.initialEnv, sem.initialStore)).toSeq,
      /* Initially we didn't visit any state */
      VisitedSet.MapVisitedSet.empty,
      /* Initially no halted state has been visited */
      Set(),
      /* Graph is initially empty, and we wrap it into an Option */
      if (graph) { Some(Graph.empty) } else { None })
  }
}

object Main {

  import Util._
  import scala.concurrent.duration.Duration

  val INPUT_DIR = "test/"
  val OUTPUT_DIR = "/Users/nvanes/Desktop/outputs/"
  val OUTPUT_PNG = false
  val WARMUP_RUNS = 5
  val TIMEOUT = Duration(60, "seconds")

  val bounded = new BoundedInteger(1)
  val lattice = new MakeSchemeLattice[Type.S, Concrete.B, Type.I, Type.F, Type.C, Type.Sym](false)
  val address = ClassicalAddress
  val time = ZeroCFA
  implicit val isTimestamp = time.isTimestamp
  val sem = new SchemeSemantics[lattice.L, address.A, time.T](new SchemePrimitives[address.A, lattice.L])

  trait GCStrategy { def name: String }
  case object NoGC extends GCStrategy { def name = "NoGC" }
  case object RefCounting extends GCStrategy { def name = "RefCounting" }
  case object ClassicalGC extends GCStrategy { def name = "ClassicalGC" }

  def main(args: Array[String]): Unit = {
    val current = "primtest"
    //benchmark(current,NoGC)
    benchmark(current,ClassicalGC)
    benchmark(current,RefCounting)
  }

  def benchmark(name: String, gcStrategy: GCStrategy): Unit = {
    val machine = gcStrategy match {
      case NoGC => new AAM[SchemeExp, lattice.L, address.A, time.T]
      case RefCounting => new AAMRefCounting[SchemeExp, lattice.L, address.A, time.T]
      case ClassicalGC => new AAMGC[SchemeExp, lattice.L, address.A, time.T]
    }
    val benchName = s"${name}-${time.isTimestamp.name}-${gcStrategy.name}"
    val file = INPUT_DIR + name + ".scm"
    replOrFile(Some(file), text => {
      val program = SchemeUtils.computeFreeVar(SchemeUtils.inline(sem.parse(text),sem.initialEnv.toMap))
      //val program = sem.parse(text)
      println(s">>> RUNNING BENCHMARK ${benchName}")
      print("warming up")
      (1 to WARMUP_RUNS).foreach( i => { print(".") ; machine.eval(program,sem,OUTPUT_PNG,Timeout.start(TIMEOUT)) })
      println()
      val t0 = System.nanoTime()
      val result = machine.eval(program,sem,OUTPUT_PNG,Timeout.start(TIMEOUT))
      val t1 = System.nanoTime()
      if (result.timedOut) {
        println("<<TIMEOUT>>")
      } else {
        println(s"states: ${result.numberOfStates}")
        println(s"elapsed: ${(t1-t0)/1000000}ms")
      }
      if (OUTPUT_PNG) { result.toPng(OUTPUT_DIR + benchName + ".png") }
      println(s"<<< FINISHED BENCHMARK ${benchName}")
    })
  }
}
