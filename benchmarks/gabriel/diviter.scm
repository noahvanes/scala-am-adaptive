(begin
   (define (create-n n) (begin (define ($do-loop0 n a) (if (= n 0) a (begin ($do-loop0 (- n 1) (cons '() a))))) ($do-loop0 n '())))
   (define *ll* (create-n 200))
   (define (iterative-div2 l) (begin (define ($do-loop1 l a) (if (null? l) a (begin ($do-loop1 (cddr l) (cons (car l) a))))) ($do-loop1 l '())))
   (equal?
    (iterative-div2 *ll*)
    '(()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ()
      ())))