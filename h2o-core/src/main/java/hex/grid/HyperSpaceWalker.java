package hex.grid;

import hex.Model;
import hex.ModelParametersBuilderFactory;
import hex.ScoreKeeper;
import hex.ScoringInfo;
import hex.grid.HyperSpaceSearchCriteria.CartesianSearchCriteria;
import hex.grid.HyperSpaceSearchCriteria.RandomDiscreteValueSearchCriteria;
import hex.grid.HyperSpaceSearchCriteria.Strategy;
import water.exceptions.H2OIllegalArgumentException;
import water.util.PojoUtils;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface HyperSpaceWalker<MP extends Model.Parameters, C extends HyperSpaceSearchCriteria> {

  interface HyperSpaceIterator<MP extends Model.Parameters> {
    /**
     * Get next model parameters.
     *
     * <p>It should return model parameters for next point in hyper space.
     * Throws {@link java.util.NoSuchElementException} if there is no remaining point in space
     * to explore.</p>
     *
     * <p>The method can optimize based on previousModel, but should be
     * able to handle null-value.</p>
     *
     * @param previousModel  model generated for the previous point in hyper space, can be null.
     *
     * @return model parameters for next point in hyper space or null if there is no such point.
     *
     * @throws IllegalArgumentException  when model parameters cannot be constructed
     * @throws java.util.NoSuchElementException if the iteration has no more elements
     */
    MP nextModelParameters(Model previousModel);

    /**
     * Returns true if the iterator can continue.  Takes into account strategy-specific stopping criteria, if any.
     * @param previousModel  optional parameter which helps to determine next step, can be null
     * @return  true if the iterator can produce one more model parameters configuration.
     */
    boolean hasNext(Model previousModel);

    /**
     * Inform the Iterator that a model build failed in case it needs to adjust its internal state.
     * Implementations are expected to consume the {@code withFailedModelHyperParams} callback with the hyperParams used to create the failed model.
     * @param failedModel: the model whose training failed.
     * @param withFailedModelHyperParams: consumes the "raw" hyperparameters values used for the failed model.
     */
    void onModelFailure(Model failedModel, Consumer<Object[]> withFailedModelHyperParams);

  } // interface HyperSpaceIterator

  /**
   * Search criteria for the hyperparameter search including directives for how to search and
   * when to stop the search.
   */
  C search_criteria();

  /** Based on the last model, the given array of ScoringInfo, and our stopping criteria should we stop early? */
  boolean stopEarly(Model model, ScoringInfo[] sk);

  /**
   * Returns an iterator to traverse this hyper-space.
   *
   * @return an iterator
   */
  HyperSpaceIterator<MP> iterator();

  /**
   * Returns hyper parameters names which are used for walking the hyper parameters space.
   *
   * The names have to match the names of attributes in model parameters MP.
   *
   * @return names of used hyper parameters
   */
  String[] getHyperParamNames();
  String[] getHyperParamNamesConstraint();

  /**
   * Return estimated maximum size of hyperspace, not subject to any early stopping criteria.
   *
   * Can return -1 if estimate is not available.
   *
   * @return size of hyper space to explore
   */
  long getMaxHyperSpaceSize();

  /**
   * Return initial model parameters for search.
   * @return  return model parameters
   */
  MP getParams();

  ModelParametersBuilderFactory<MP> getParametersBuilderFactory();

  /**
   * Superclass for for all hyperparameter space walkers.
   * <p>
   * The external Grid / Hyperparameter search API uses a HashMap<String,Object> to describe a set of hyperparameter
   * values, where the String is a valid field name in the corresponding Model.Parameter, and the Object is
   * the field value (boxed as needed).
   */
  abstract class BaseWalker<MP extends Model.Parameters, C extends HyperSpaceSearchCriteria> implements HyperSpaceWalker<MP, C> {

    /**
     * @see #search_criteria()
     */
    final protected C _search_criteria;

    public static final String CONSTRAINTS = "constraints";
    
    /**
     * Search criteria for the hyperparameter search including directives for how to search and
     * when to stop the search.
     */
    public C search_criteria() { return _search_criteria; }
    
    /** Based on the last model, the given array of ScoringInfo, and our stopping criteria should we stop early? */
    @Override
    public boolean stopEarly(Model model, ScoringInfo[] sk) {
      return false;
    }

    /**
     * Parameters builder factory to create new instance of parameters.
     */
    final transient ModelParametersBuilderFactory<MP> _paramsBuilderFactory;

    /**
     * Used "base" model parameters for this grid search.
     * The object is used as a prototype to create model parameters
     * for each point in hyper space.
     */
    final MP _params;

    final MP _defaultParams;

    /**
     * Hyper space description - in this case only dimension and possible values.
     */
    final protected Map<String, Object[]> _hyperParams;

    long model_number = 0l;   // denote model number
    /**
     * Cached names of used hyper parameters.
     */
    final protected String[] _hyperParamNames;
    
    final protected String[] _hyperParamNamesConstraint;  // model parameters specified in constraints of hyper parameters

    protected Map<String, Object[]>[] _hyperParamConstraints;
    /**
     * Compute max size of hyper space to walk. May include duplicates if points in space are specified multiple
     * times.
     */
    final protected long _maxHyperSpaceSize;

    /**
     * Java hackery so we can have a factory method on a class with type params.
     */
    public static class WalkerFactory<MP extends Model.Parameters, C extends HyperSpaceSearchCriteria> {
      /**
       * Factory method to create an instance based on the given HyperSpaceSearchCriteria instance.
       */
      public static <MP extends Model.Parameters, C extends HyperSpaceSearchCriteria>
      HyperSpaceWalker<MP, ? extends HyperSpaceSearchCriteria> create(MP params,
                                                                      Map<String, Object[]> hyperParams,
                                                                      ModelParametersBuilderFactory<MP> paramsBuilderFactory,
                                                                      C search_criteria) {
        Strategy strategy = search_criteria.strategy();
        switch (strategy) {
          case Cartesian:
            return new HyperSpaceWalker.CartesianWalker<>(params, hyperParams, paramsBuilderFactory, (CartesianSearchCriteria) search_criteria);
          case RandomDiscrete:
            return new HyperSpaceWalker.RandomDiscreteValueWalker<>(params, hyperParams, paramsBuilderFactory, (RandomDiscreteValueSearchCriteria) search_criteria);
          default:
            throw new H2OIllegalArgumentException("strategy", "GridSearch", strategy);
        }
      }
    }

    /**
     *
     * @param paramsBuilderFactory
     * @param hyperParams
     */
    public BaseWalker(MP params,
                      Map<String, Object[]> hyperParams,
                      ModelParametersBuilderFactory<MP> paramsBuilderFactory,
                      C search_criteria) {
      _params = params;
      _hyperParams = hyperParams;
      _paramsBuilderFactory = paramsBuilderFactory;
      _hyperParamNames = hyperParams.keySet().toArray(new String[0]);
      _hyperParamConstraints = extractConstraints();
      _hyperParamNamesConstraint = extractConstraintNames();
      if(_hyperParams.get(CONSTRAINTS) != null) { _hyperParams.remove(CONSTRAINTS); }
      _search_criteria = search_criteria;
      _maxHyperSpaceSize = computeMaxSizeOfHyperSpace();
      
      // Sanity check the hyperParams map, and check it against the params object
      try {
        _defaultParams = (MP) params.getClass().newInstance();
      } catch (Exception e) {
        throw new H2OIllegalArgumentException("Failed to instantiate a new Model.Parameters object to get the default values.");
      }
      validateParams(_hyperParams, false);
      Arrays.stream(_hyperParamConstraints).forEach(constraint -> validateParams(constraint, true));
    } // BaseWalker()

    @Override
    public String[] getHyperParamNames() {
      return _hyperParamNames;
    }
    
    public String[] getHyperParamNamesConstraint() {
      return _hyperParamNamesConstraint;
    }

    @Override
    public long getMaxHyperSpaceSize() {
      return _maxHyperSpaceSize;
    }

    @Override
    public MP getParams() {
      return _params;
    }

    @Override
    public ModelParametersBuilderFactory<MP> getParametersBuilderFactory() {
      return _paramsBuilderFactory;
    }
    
    private Map<String, Object[]>[] extractConstraints() {
      if(_hyperParams.get(CONSTRAINTS) == null) { return new Map[0]; }
      Map<String, Object[]>[] constraints = new Map[_hyperParams.get(CONSTRAINTS).length];
      for (int i = 0; i < _hyperParams.get(CONSTRAINTS).length; i++) {
        constraints[i] = (Map<String, Object[]>)_hyperParams.get(CONSTRAINTS)[i];
      }
      return constraints;
    }
    
    private String[] extractConstraintNames() {
      return Stream.of(_hyperParamConstraints)
              .flatMap(m -> m.keySet().stream())
              .toArray(String[]::new);
    }
    
    protected MP getModelParams(MP params, Object[] hyperParams, String[] hyperParamNames) {
      ModelParametersBuilderFactory.ModelParametersBuilder<MP>
              paramsBuilder = _paramsBuilderFactory.get(params);
      for (int i = 0; i < hyperParamNames.length; i++) {
        String paramName = hyperParamNames[i];
        Object paramValue = hyperParams[i];
        if (paramName.equals("valid")) {  // change paramValue to key<Frame> for validation_frame
          paramName = "validation_frame";   // @#$, paramsSchema is still using validation_frame and training_frame
        }

        paramsBuilder.set(paramName, paramValue);
      }
      return paramsBuilder.build();
    }

    protected long computeMaxSizeOfHyperSpace() {
      long work = 0;
      long free_param_combos = 1;

      for (Map<String, Object[]> constraint : _hyperParamConstraints) {
        long constrained_param_combos = 1;
        for (Object[] o : constraint.values()) {
          constrained_param_combos *= o.length;
        }
        work += constrained_param_combos;
      } // work will be zero if there is no constraints in hyper parameters

      for (Object[] p : _hyperParams.values()) {
        free_param_combos *= p.length;
      }

      work = work == 0 ? free_param_combos : free_param_combos * work;

      return work;
    }

    protected Map<String, Object[]> mergeHashMaps(Map<String, Object[]> hyperparams, Map<String, Object[]> constraints) {
      if(constraints == null) { return hyperparams; }
      Map<String, Object[]> m = new HashMap<>();
      m.putAll(hyperparams);
      m.putAll(constraints);
      return m;
    }
    
    /** Given a list of indices for the hyperparameter values return an Object[] of the actual values. */
    protected Object[] hypers(Map<String, Object[]> hyperParams, String[] hyperParamNames, int[] hidx) {
      Object[] hypers = new Object[hyperParamNames.length];
      for (int i = 0; i < hidx.length; i++) {
        hypers[i] = hyperParams.get(hyperParamNames[i])[hidx[i]];
      }
      return hypers;
    }

    protected int integerHash(Map<String, Object[]> hyperParams, String[] hyperParamNames, int[] ar, int constraintNum) {
      Integer[] hashMe = new Integer[ar.length + 1];
      for (int i = 0; i < ar.length; i++)
        hashMe[i] = ar[i] * hyperParams.get(hyperParamNames[i]).length;
      hashMe[ar.length] = constraintNum;
      return Arrays.deepHashCode(hashMe);
    }

    private void validateParams(Map<String, Object[]> params, boolean isConstraint) {
      // if a parameter is specified in both model parameter and hyper-parameter, this is only allowed if the
      // parameter value is set to be default.  Otherwise, an exception will be thrown.
      for (String key : params.keySet()) {
        // Throw if the user passed an empty value list:
        Object[] values = params.get(key);
        if (0 == values.length)
          throw new H2OIllegalArgumentException("Grid search hyperparameter value list is empty for hyperparameter: " + key);

        if ("seed".equals(key) || "_seed".equals(key)) continue;  // initialized to the wall clock

        if (isConstraint && _hyperParams.get(key) != null) {
          throw new H2OIllegalArgumentException("Grid search model parameter '" + key + "' is set in " +
                  "both the constraints and in the hyperparameters map.  This is ambiguous; set it in one place" +
                  " or the other, not both.");
        }
        
        validateParamVals(key);
        // Ugh.  Java callers, like the JUnits or Sparkling Water users, use a leading _.  REST users don't.
      }
    }
    
    private void validateParamVals(String key) {
      String prefix = (key.startsWith("_") ? "" : "_");

      // Throw if params has a non-default value which is not in the hyperParams map
      Object defaultVal = PojoUtils.getFieldValue(_defaultParams, prefix + key, PojoUtils.FieldNaming.CONSISTENT);
      Object actualVal = PojoUtils.getFieldValue(_params, prefix + key, PojoUtils.FieldNaming.CONSISTENT);

      if (defaultVal != null && actualVal != null) {
        // both are not set to null
        if (defaultVal.getClass().isArray() &&
                // array
                !PojoUtils.arraysEquals(defaultVal, actualVal)) {
          throw new H2OIllegalArgumentException("Grid search model parameter '" + key + "' is set in both the model parameters and in the hyperparameters map.  This is ambiguous; set it in one place or the other, not both.");
        } // array
        if (!defaultVal.getClass().isArray() &&
                // ! array
                !defaultVal.equals(actualVal)) {
          throw new H2OIllegalArgumentException("Grid search model parameter '" + key + "' is set in both the model parameters and in the hyperparameters map.  This is ambiguous; set it in one place or the other, not both.");
        } // ! array
      } // both are set: defaultVal != null && actualVal != null

      // defaultVal is null but actualVal is not, raise exception
      if (defaultVal == null && !(actualVal == null)) {
        // only actual is set
        throw new H2OIllegalArgumentException("Grid search model parameter '" + key + "' is set in both the model parameters and in the hyperparameters map.  This is ambiguous; set it in one place or the other, not both.");
      }
    }
  }

  /**
   * Hyperparameter space walker which visits each combination of hyperparameters in order.
   */
  class CartesianWalker<MP extends Model.Parameters>
          extends BaseWalker<MP, CartesianSearchCriteria> {

    public CartesianWalker(MP params,
                           Map<String, Object[]> hyperParams,
                           ModelParametersBuilderFactory<MP> paramsBuilderFactory,
                           CartesianSearchCriteria search_criteria) {
      super(params, hyperParams, paramsBuilderFactory, search_criteria);
    }

    @Override
    public HyperSpaceIterator<MP> iterator() {

      return new HyperSpaceIterator<MP>() {
        /** Hyper params permutation.
         */
        private int[] _currentHyperparamIndices = null;
        private int _currentConstraint = _hyperParamConstraints.length == 0 ? -1 : 0;
        private Map<String, Object[]> _currentHyperParams = _hyperParamConstraints.length == 0 ? 
                _hyperParams : mergeHashMaps(_hyperParams, _hyperParamConstraints[0]);
        private String[] _currentHyperParamNames = _currentHyperParams.keySet().toArray(new String[0]);

        @Override
        public MP nextModelParameters(Model previousModel) {
          _currentHyperparamIndices = _currentHyperparamIndices == null ?
                  new int[_currentHyperParamNames.length] : nextModelIndices(_currentHyperparamIndices);
          
          if(_currentConstraint < _hyperParamConstraints.length - 1 && _currentHyperparamIndices == null) { // getting to next constraints here
            _currentHyperParams = mergeHashMaps(_hyperParams, _hyperParamConstraints[++_currentConstraint]);
            _currentHyperParamNames = _currentHyperParams.keySet().toArray(new String[0]);
            _currentHyperparamIndices = new int[_currentHyperParamNames.length];
          }

          if (_currentHyperparamIndices != null) {
            // Fill array of hyper-values
            Object[] hypers = hypers(_currentHyperParams, _currentHyperParamNames, _currentHyperparamIndices);
            // Get clone of parameters
            MP commonModelParams = (MP) _params.clone();
            // Fill model parameters
            MP params = getModelParams(commonModelParams, hypers, _currentHyperParamNames);

            return params;
          } else {
            throw new NoSuchElementException("No more elements to explore in hyper-space!");
          }
        }

        @Override
        public boolean hasNext(Model previousModel) {
          // Checks to see that there is another valid combination of hyper parameters left in the hyperspace.
          if (_currentHyperparamIndices != null) {
            int[] hyperParamIndicesCopy = new int[_currentHyperparamIndices.length];
            System.arraycopy(_currentHyperparamIndices, 0, hyperParamIndicesCopy, 0, _currentHyperparamIndices.length);
            if (nextModelIndices(hyperParamIndicesCopy) == null) {
              if(_currentConstraint == _hyperParamConstraints.length - 1) {
                return false;  
              }
            }
          }
          
          return true;
        }

        @Override
        public void onModelFailure(Model failedModel, Consumer<Object[]> withFailedModelHyperParams) {
          // FIXME: when using parallel grid search, there's no good reason to think that the current hyperparam indices where the ones used for the failed model
          withFailedModelHyperParams.accept(hypers(_currentHyperParams, _currentHyperParamNames, _currentHyperparamIndices));
        }

        /**
         * Cartesian iteration over the hyper-parameter space, varying one hyperparameter at a
         * time. Mutates the indices that are passed in and returns them.  Returns NULL when
         * the entire space has been traversed.
         */
        private int[] nextModelIndices(int[] hyperparamIndices) {
          // Find the next parm to flip
          int i;
          for (i = 0; i < hyperparamIndices.length; i++) {
            if (hyperparamIndices[i] + 1 < _currentHyperParams.get(_currentHyperParamNames[i]).length) {
              break;
            }
          }
          if (i == hyperparamIndices.length) {
            return null; // All done, report null
          }
          // Flip indices
          for (int j = 0; j < i; j++) {
            hyperparamIndices[j] = 0;
          }
          
          hyperparamIndices[i]++;
          return hyperparamIndices;
        }
      }; // anonymous HyperSpaceIterator class
    } // iterator()

  } // class CartesianWalker

  /**
   * Hyperparameter space walker which visits random combinations of hyperparameters whose possible values are
   * given in explicit lists as they are with CartesianWalker.
   */
  class RandomDiscreteValueWalker<MP extends Model.Parameters>
          extends BaseWalker<MP, RandomDiscreteValueSearchCriteria> {

    private Random _random;
    private boolean _set_model_seed_from_search_seed;  // true if model parameter seed is set to default value and false otherwise

    public RandomDiscreteValueWalker(MP params,
                                     Map<String, Object[]> hyperParams,
                                     ModelParametersBuilderFactory<MP> paramsBuilderFactory,
                                     RandomDiscreteValueSearchCriteria search_criteria) {
      super(params, hyperParams, paramsBuilderFactory, search_criteria);

      // seed the models using the search seed if it is the only one specified
      long defaultSeed = _defaultParams._seed;
      long actualSeed = _params._seed;
      long gridSeed = search_criteria.seed();
      _set_model_seed_from_search_seed = defaultSeed == actualSeed && defaultSeed != gridSeed;
      _random = gridSeed == defaultSeed ? new Random() : new Random(gridSeed);
    }

    /** Based on the last model, the given array of ScoringInfo, and our stopping criteria should we stop early? */
    @Override
    public boolean stopEarly(Model model, ScoringInfo[] sk) {
      return ScoreKeeper.stopEarly(ScoringInfo.scoreKeepers(sk),
              search_criteria().stopping_rounds(),
              ScoreKeeper.ProblemType.forSupervised(model._output.isClassifier()),
              search_criteria().stopping_metric(),
              search_criteria().stopping_tolerance(), "grid's best", true);
    }

    @Override
    public HyperSpaceIterator<MP> iterator() {
      return new HyperSpaceIterator<MP>() {
        /** All visited hyper params permutations, including the current one. */
        private final List<int[]> _visitedPermutations = new ArrayList<>();
        private final Set<Integer> _visitedPermutationHashes = new LinkedHashSet<>(); // for fast dupe lookup

        /** Current hyper params permutation. */
        private int[] _currentHyperparamIndices = null;

        /** One-based count of the permutations we've visited, primarily used as an index into _visitedHyperparamIndices. */
        private int _currentPermutationNum = 0;

        private int _currentConstraint = -1;
        private Map<String, Object[]> _currentHyperParams = _hyperParams;
        private String[] _currentHyperParamNames = _hyperParamNames;

        // TODO: override into a common subclass:
        @Override
        public MP nextModelParameters(Model previousModel) {
          // NOTE: nextModel checks _visitedHyperparamIndices and does not return a duplicate set of indices.
          // NOTE: in RandomDiscreteValueWalker nextModelIndices() returns a new array each time, rather than
          // mutating the last one.
          _currentHyperparamIndices = nextModelIndices();

          if (_currentHyperparamIndices != null) {
            _visitedPermutations.add(_currentHyperparamIndices);
            _visitedPermutationHashes.add(integerHash(_currentHyperParams, _currentHyperParamNames, _currentHyperparamIndices, _currentConstraint));
            _currentPermutationNum++; // NOTE: 1-based counting

            // Fill array of hyper-values
            Object[] hypers = hypers(_currentHyperParams, _currentHyperParamNames, _currentHyperparamIndices);
            // Get clone of parameters
            MP commonModelParams = (MP) _params.clone();
            // Fill model parameters
            MP params = getModelParams(commonModelParams, hypers, _currentHyperParamNames);

            // add max_runtime_secs in search criteria into params if applicable
            if (_search_criteria != null && _search_criteria.strategy() == Strategy.RandomDiscrete) {
              // ToDo: model seed setting will be different for parallel model building.
              // ToDo: This implementation only works for sequential model building.
              if (_set_model_seed_from_search_seed) {
                // set model seed = search_criteria.seed+(0, 1, 2,..., model number)
                params._seed = _search_criteria.seed() + (model_number++);
              }
            }
            return params;
          } else {
            throw new NoSuchElementException("No more elements to explore in hyper-space!");
          }
        }

        @Override
        public boolean hasNext(Model previousModel) {
          // Note: we compare _currentPermutationNum to max_models, because it counts successfully created models, but
          // we compare _visitedPermutationHashes.size() to _maxHyperSpaceSize because we want to stop when we have attempted each combo.
          //
          // _currentPermutationNum is 1-based
          return (_visitedPermutationHashes.size() < _maxHyperSpaceSize &&
                  (search_criteria().max_models() == 0 || _currentPermutationNum < search_criteria().max_models())
          );
        }

        @Override
        public void onModelFailure(Model failedModel, Consumer<Object[]> withFailedModelHyperParams) {
          // FIXME: when using parallel grid search, there's no good reason to think that the current hyperparam indices where the ones used for the failed model
          _currentPermutationNum--;
          withFailedModelHyperParams.accept(hypers(_currentHyperParams, _currentHyperParamNames, _currentHyperparamIndices));
        }

        /**
         * Random iteration over the hyper-parameter space.  Does not repeat
         * previously-visited combinations.  Returns NULL when we've hit the stopping
         * criteria.
         */
        private int[] nextModelIndices() {
          
          int[] hyperparamIndices = new int[_currentHyperParamNames.length];
          
          do {
            if(_hyperParamConstraints.length != 0) {
              _currentConstraint = _random.nextInt(_hyperParamConstraints.length);
              _currentHyperParams = mergeHashMaps(_hyperParams, _hyperParamConstraints[_currentConstraint]);
              _currentHyperParamNames = _currentHyperParams.keySet().toArray(new String[0]);
              hyperparamIndices = new int[_currentHyperParamNames.length];
            }
            for (int i = 0; i < _currentHyperParamNames.length; i++) {
              hyperparamIndices[i] = _random.nextInt(_currentHyperParams.get(_currentHyperParamNames[i]).length);
            }
            // check for aliases and loop if we've visited this combo before
          } while (_visitedPermutationHashes.contains(integerHash(_currentHyperParams, _currentHyperParamNames, hyperparamIndices, _currentConstraint)));
          
          return hyperparamIndices;
        } // nextModel

      }; // anonymous HyperSpaceIterator class
    } // iterator()

  } // RandomDiscreteValueWalker
}
